package net.medievalrp.omniscience2.plugin.command.param;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.medievalrp.omniscience2.api.Omniscience2Api;
import net.medievalrp.omniscience2.api.param.ParamParseException;
import net.medievalrp.omniscience2.api.param.QueryParamHandler;
import net.medievalrp.omniscience2.api.query.Flag;
import net.medievalrp.omniscience2.api.query.QueryPredicate;
import net.medievalrp.omniscience2.api.query.QueryRequest;
import net.medievalrp.omniscience2.api.query.Sort;
import net.medievalrp.omniscience2.api.util.BlockLocation;
import net.medievalrp.omniscience2.api.util.Duration;
import net.medievalrp.omniscience2.plugin.config.Omniscience2Config;
import net.medievalrp.omniscience2.plugin.util.BlockLocations;
import net.medievalrp.omniscience2.plugin.worldedit.WorldEditSelection;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class QueryStringParser {

    private final Omniscience2Api api;
    private final Omniscience2Config config;

    public QueryStringParser(Omniscience2Api api, Omniscience2Config config) {
        this.api = api;
        this.config = config;
    }

    public QueryRequest parse(CommandSender sender, String raw, int overrideLimit) throws ParamParseException {
        BlockLocation senderLocation = senderLocation(sender);
        QueryParamHandler.ParamContext context = new QueryParamHandler.ParamContext(sender, senderLocation, config.limits().maxRadius());

        List<QueryPredicate> predicates = new ArrayList<>();
        EnumSet<Flag> flags = EnumSet.noneOf(Flag.class);
        Sort sort = Sort.NEWEST_FIRST;
        Set<String> usedHandlerAliases = new LinkedHashSet<>();
        boolean defaultRadiusSuppressed = false;
        boolean sawTime = false;
        boolean global = false;

        if (raw != null && !raw.isBlank()) {
            for (String token : raw.trim().split("\\s+")) {
                if (token.isEmpty()) {
                    continue;
                }
                if (token.startsWith("-")) {
                    String name = token.substring(1).toLowerCase();
                    String flagValue = null;
                    int eq = name.indexOf('=');
                    if (eq >= 0) {
                        flagValue = name.substring(eq + 1);
                        name = name.substring(0, eq);
                    }
                    switch (name) {
                        case "ng", "nogroup" -> flags.add(Flag.NO_GROUP);
                        case "g", "global" -> {
                            flags.add(Flag.GLOBAL);
                            global = true;
                            defaultRadiusSuppressed = true;
                        }
                        case "nc", "nochat" -> flags.add(Flag.NO_CHAT);
                        case "ex", "extended" -> flags.add(Flag.EXTENDED);
                        case "we", "worldedit" -> {
                            if (!(sender instanceof Player player)) {
                                throw new ParamParseException("Flag -we requires a player with a WorldEdit selection.");
                            }
                            if (!sender.hasPermission("omniscience2.worldedit")) {
                                throw new ParamParseException("Missing permission omniscience2.worldedit.");
                            }
                            if (!isWorldEditLoaded()) {
                                throw new ParamParseException("WorldEdit is not installed.");
                            }
                            WorldEditSelection.Box box = WorldEditSelection.currentBox(player);
                            if (box == null) {
                                throw new ParamParseException("No active WorldEdit selection.");
                            }
                            predicates.add(cuboid(box));
                            defaultRadiusSuppressed = true;
                        }
                        case "ord", "order" -> {
                            if (flagValue == null) {
                                throw new ParamParseException("Flag -ord requires a value (asc/desc).");
                            }
                            sort = switch (flagValue) {
                                case "asc", "old", "oldest" -> Sort.OLDEST_FIRST;
                                case "desc", "new", "newest" -> Sort.NEWEST_FIRST;
                                default -> throw new ParamParseException("Unknown order: " + flagValue);
                            };
                        }
                        case "nod", "nodefault" -> {
                            // `-nod=r,t` suppresses the per-param defaults
                            // for the given aliases. v1 used this so
                            // operators could skip the default radius
                            // OR default time without disabling the
                            // config-level `defaults.enabled` switch.
                            if (flagValue == null || flagValue.isBlank()) {
                                throw new ParamParseException(
                                        "Flag -nod requires a value (comma-separated param aliases).");
                            }
                            for (String aliasRaw : flagValue.split(",")) {
                                String paramAlias = aliasRaw.trim();
                                if (paramAlias.isEmpty()) {
                                    continue;
                                }
                                switch (paramAlias) {
                                    case "r", "radius" -> defaultRadiusSuppressed = true;
                                    case "t", "since", "time" -> sawTime = true;
                                    default -> throw new ParamParseException(
                                            "Flag -nod: unknown default-bearing alias: " + paramAlias);
                                }
                            }
                        }
                        default -> throw new ParamParseException("Unknown flag: -" + name);
                    }
                    continue;
                }

                String alias;
                String value;
                int colon = token.indexOf(':');
                if (colon < 0) {
                    alias = "p";
                    value = token;
                } else {
                    alias = token.substring(0, colon).toLowerCase();
                    value = token.substring(colon + 1);
                }

                Optional<QueryParamHandler> handler = api.queryParam(alias);
                if (handler.isEmpty()) {
                    throw new ParamParseException("Unknown parameter: " + alias);
                }
                QueryParamHandler h = handler.get();
                if (usedHandlerAliases.contains(canonicalAlias(h))) {
                    throw new ParamParseException("Duplicate parameter: " + alias);
                }
                QueryPredicate predicate = h.parse(alias, value, context);
                predicates.add(predicate);
                usedHandlerAliases.add(canonicalAlias(h));
                if (h.suppressesDefaultRadius(alias)) {
                    defaultRadiusSuppressed = true;
                }
                if (h instanceof TimeParam) {
                    sawTime = true;
                }
            }
        }

        if (config.defaults().enabled()) {
            if (!defaultRadiusSuppressed && !global && senderLocation != null) {
                predicates.add(RadiusParam.groupAround(senderLocation, config.defaults().radius()));
            }
            if (!sawTime) {
                Duration defaultTime = config.defaults().time();
                Instant lower = defaultTime.before(Instant.now());
                predicates.add(new QueryPredicate.Range("occurred", lower, null));
            }
        }

        int limit = overrideLimit > 0 ? overrideLimit : config.limits().searchResult();
        boolean grouping = !flags.contains(Flag.NO_GROUP);
        return new QueryRequest(predicates, sort, limit, flags, grouping);
    }

    public static BlockLocation senderLocation(CommandSender sender) {
        if (sender instanceof Player player) {
            return BlockLocations.fromLocation(player.getLocation());
        }
        return null;
    }

    private static boolean isWorldEditLoaded() {
        return Bukkit.getPluginManager().getPlugin("WorldEdit") != null
                || Bukkit.getPluginManager().getPlugin("FastAsyncWorldEdit") != null;
    }

    private static QueryPredicate cuboid(WorldEditSelection.Box box) {
        BlockLocation min = box.min();
        BlockLocation max = box.max();
        return new QueryPredicate.And(List.of(
                new QueryPredicate.Eq("location.worldId", box.worldId()),
                new QueryPredicate.Range("location.x", min.x(), max.x()),
                new QueryPredicate.Range("location.y", min.y(), max.y()),
                new QueryPredicate.Range("location.z", min.z(), max.z())));
    }

    private static String canonicalAlias(QueryParamHandler handler) {
        return handler.aliases().getFirst();
    }
}
