package net.medievalrp.spyglass.plugin.command.param;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.medievalrp.spyglass.api.SpyglassApi;
import net.medievalrp.spyglass.api.extension.FlagHandler;
import net.medievalrp.spyglass.api.param.ParamParseException;
import net.medievalrp.spyglass.api.param.QueryParamHandler;
import net.medievalrp.spyglass.api.query.Flag;
import net.medievalrp.spyglass.api.query.QueryPredicate;
import net.medievalrp.spyglass.api.query.QueryRequest;
import net.medievalrp.spyglass.api.query.Sort;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.api.util.Duration;
import net.medievalrp.spyglass.plugin.config.SpyglassConfig;
import net.medievalrp.spyglass.plugin.util.BlockLocations;
import net.medievalrp.spyglass.plugin.worldedit.WorldEditSelection;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class QueryStringParser {

    private final SpyglassApi api;
    private final SpyglassConfig config;

    public QueryStringParser(SpyglassApi api, SpyglassConfig config) {
        this.api = api;
        this.config = config;
    }

    public QueryRequest parse(CommandSender sender, String raw, int overrideLimit) throws ParamParseException {
        return parse(sender, raw, overrideLimit, null);
    }

    /**
     * Scan a raw query for {@code ip:} values without running any resolution.
     * The service layer calls this on the main thread, resolves the addresses
     * off-thread, and hands the result back to {@link #parse(CommandSender,
     * String, int, Map)} so the blocking store lookup never sits on the tick.
     * Malformed input (e.g. an unterminated quote) yields an empty list; the
     * real {@link #parse} reports the error.
     */
    public List<String> extractIpValues(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> ips = new ArrayList<>();
        try {
            for (String token : tokenize(raw.trim())) {
                int colon = token.indexOf(':');
                if (colon < 0) {
                    continue;
                }
                String alias = token.substring(0, colon).toLowerCase(java.util.Locale.ROOT);
                if (alias.equals("ip")) {
                    String value = token.substring(colon + 1).trim();
                    if (!value.isBlank()) {
                        ips.add(value);
                    }
                }
            }
        } catch (ParamParseException ex) {
            return List.of();
        }
        return ips;
    }

    /**
     * @param resolvedIps IP -> player UUIDs pre-resolved off-thread (see
     *     {@code IpQueryResolver}); passed through to {@link IpParam} so it
     *     does not query the store on the command thread. Null when there is
     *     nothing to pre-resolve.
     */
    public QueryRequest parse(CommandSender sender, String raw, int overrideLimit,
                              Map<String, List<UUID>> resolvedIps) throws ParamParseException {
        BlockLocation senderLocation = senderLocation(sender);
        QueryParamHandler.ParamContext context = new QueryParamHandler.ParamContext(sender, senderLocation, config.limits().maxRadius());

        ParseState state = new ParseState();

        if (raw != null && !raw.isBlank()) {
            for (String token : tokenize(raw.trim())) {
                if (token.isEmpty()) {
                    continue;
                }
                if (token.startsWith("-")) {
                    String name = token.substring(1).toLowerCase(java.util.Locale.ROOT);
                    String flagValue = null;
                    int eq = name.indexOf('=');
                    if (eq >= 0) {
                        flagValue = name.substring(eq + 1);
                        name = name.substring(0, eq);
                    }
                    applyFlag(name, flagValue, sender, context, state);
                    continue;
                }

                String alias;
                String value;
                int colon = token.indexOf(':');
                if (colon < 0) {
                    alias = "p";
                    value = token;
                } else {
                    alias = token.substring(0, colon).toLowerCase(java.util.Locale.ROOT);
                    value = token.substring(colon + 1);
                }

                // v1-compat: {@code we:1}, {@code ord:asc}, {@code nod:r}
                // were flags-by-colon in v1. Route them through the same
                // flag handler so muscle memory keeps working.
                if (isFlagAlias(alias)) {
                    applyFlag(alias, value, sender, context, state);
                    continue;
                }

                Optional<QueryParamHandler> handler = api.queryParam(alias);
                if (handler.isEmpty()) {
                    throw new ParamParseException("Unknown parameter: " + alias);
                }
                QueryParamHandler h = handler.get();
                if (state.usedHandlerAliases.contains(canonicalAlias(h))) {
                    throw new ParamParseException("Duplicate parameter: " + alias);
                }
                // IpParam resolves IP -> player UUIDs against the store. When the
                // service pre-resolved off-thread, hand it the map so it doesn't
                // query on the command thread; otherwise it falls back inline.
                QueryPredicate predicate = (h instanceof IpParam ipHandler)
                        ? ipHandler.parse(alias, value, context, resolvedIps)
                        : h.parse(alias, value, context);
                state.predicates.add(predicate);
                state.usedHandlerAliases.add(canonicalAlias(h));
                if (h.suppressesDefaultRadius(alias)) {
                    state.defaultRadiusSuppressed = true;
                }
                if (h instanceof TimeParam) {
                    state.sawTime = true;
                }
            }
        }
        List<QueryPredicate> predicates = state.predicates;
        EnumSet<Flag> flags = state.flags;
        Sort sort = state.sort;
        boolean defaultRadiusSuppressed = state.defaultRadiusSuppressed;
        boolean sawTime = state.sawTime;
        boolean global = state.global;

        if (config.defaults().enabled()) {
            int defaultRadius = config.defaults().radius();
            if (!defaultRadiusSuppressed && !global && senderLocation != null) {
                if (defaultRadius > 0) {
                    predicates.add(RadiusParam.groupAround(senderLocation, defaultRadius));
                    // Hint, not an error: tell the player the default kicked in
                    // and how to override. Operators upgrading from v1 muscle-
                    // memory used to type bare /spyglass search expecting global
                    // and got the default radius back; this nudge preempts the
                    // "why are there no results" follow-up. Suppressed when
                    // defaults.radius=0, which is the operator's "I want
                    // global by default" opt-out.
                    sender.sendMessage(net.kyori.adventure.text.Component.text(
                            "(no range; defaulting to " + defaultRadius
                                    + " blocks - add r:N or -g for global)",
                            net.kyori.adventure.text.format.NamedTextColor.DARK_GRAY));
                }
                // defaultRadius == 0 means "global default" — no predicate
                // added, no reminder shown. The whole-DB scan is what the
                // operator opted into.
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

    /**
     * Split a raw query into tokens on whitespace, but keep a double-quoted
     * span as a single token so multi-word values survive: {@code iname:"flaming
     * sword"} becomes the one token {@code iname:flaming sword}. The quote
     * characters themselves are stripped; whitespace inside the quotes is kept.
     *
     * <p>Only the double quote {@code "} is a delimiter. The apostrophe is left
     * literal on purpose - fantasy item names routinely contain one (Maker's
     * Blade, Dragon's Breath), and an unquoted {@code iname:Maker's} must keep
     * parsing as the literal value {@code Maker's} rather than opening a span.
     *
     * <p>Escaping a literal quote (e.g. an inch mark in a name) is not
     * supported; an unbalanced quote is a user error and is reported as one.
     */
    static List<String> tokenize(String raw) throws ParamParseException {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inToken = false;
        boolean inQuote = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '"') {
                inQuote = !inQuote;
                inToken = true; // a quoted span is a token even if empty ("")
                continue;
            }
            if (!inQuote && Character.isWhitespace(c)) {
                if (inToken) {
                    tokens.add(current.toString());
                    current.setLength(0);
                    inToken = false;
                }
                continue;
            }
            current.append(c);
            inToken = true;
        }
        if (inQuote) {
            throw new ParamParseException(
                    "Unterminated quote in query - close the \" around a multi-word value"
                            + " like iname:\"flaming sword\".");
        }
        if (inToken) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    private static boolean isFlagAlias(String alias) {
        return switch (alias) {
            case "we", "worldedit",
                 "ord", "order",
                 "nod", "nodefault",
                 "ng", "nogroup",
                 "g", "global",
                 "nc", "nochat",
                 "ex", "extended" -> true;
            default -> false;
        };
    }

    /** Reserved flag aliases — {@link #applyFlag} handles these
     *  directly and a custom {@link FlagHandler} cannot shadow them. */
    private static boolean isBuiltinFlag(String alias) {
        return switch (alias) {
            case "ng", "nogroup",
                 "g", "global",
                 "nc", "nochat",
                 "ex", "extended",
                 "we", "worldedit",
                 "ord", "order",
                 "nod", "nodefault" -> true;
            default -> false;
        };
    }

    private void applyFlag(String name, String flagValue, CommandSender sender,
                           QueryParamHandler.ParamContext context, ParseState state)
            throws ParamParseException {
        // Built-ins win over custom flags. A third-party plugin can't
        // shadow `-g` by registering a `g` FlagHandler — the parser
        // checks the switch below first and the lookup never runs.
        if (!isBuiltinFlag(name)) {
            Optional<FlagHandler> handler = api.flag(name);
            if (handler.isPresent()) {
                FlagHandler h = handler.get();
                QueryPredicate predicate = h.parse(name, flagValue, context);
                state.predicates.add(predicate);
                if (h.suppressesDefaultRadius(name)) {
                    state.defaultRadiusSuppressed = true;
                }
                return;
            }
        }
        switch (name) {
            case "ng", "nogroup" -> state.flags.add(Flag.NO_GROUP);
            case "g", "global" -> {
                state.flags.add(Flag.GLOBAL);
                state.global = true;
                state.defaultRadiusSuppressed = true;
            }
            case "nc", "nochat" -> state.flags.add(Flag.NO_CHAT);
            case "ex", "extended" -> state.flags.add(Flag.EXTENDED);
            case "we", "worldedit" -> {
                if (!(sender instanceof Player player)) {
                    throw new ParamParseException("Flag we requires a player with a WorldEdit selection.");
                }
                if (!sender.hasPermission("spyglass.worldedit")) {
                    throw new ParamParseException("Missing permission spyglass.worldedit.");
                }
                if (!isWorldEditLoaded()) {
                    throw new ParamParseException("WorldEdit is not installed.");
                }
                WorldEditSelection.Box box = WorldEditSelection.currentBox(player);
                if (box == null) {
                    throw new ParamParseException("No active WorldEdit selection.");
                }
                // Cap per-axis span at {@code 2 * maxRadius}: a cuboid that
                // exceeds the same bounding box as the largest legitimate
                // {@code r:N} sphere would do unbounded work in the DB.
                // Without this, a player who selects from -30M to +30M
                // dispatches a 60M-block range query that wedges Mongo /
                // ClickHouse for minutes.
                int maxAxis = config.limits().maxRadius() * 2;
                long spanX = (long) box.max().x() - box.min().x();
                long spanY = (long) box.max().y() - box.min().y();
                long spanZ = (long) box.max().z() - box.min().z();
                if (spanX > maxAxis || spanY > maxAxis || spanZ > maxAxis) {
                    throw new ParamParseException(
                            "WorldEdit selection too large (max " + maxAxis
                                    + " blocks per axis; got " + spanX + "x" + spanY + "x" + spanZ + ").");
                }
                state.predicates.add(cuboid(box));
                state.defaultRadiusSuppressed = true;
            }
            case "ord", "order" -> {
                if (flagValue == null) {
                    throw new ParamParseException("Flag ord requires a value (asc/desc).");
                }
                state.sort = switch (flagValue) {
                    case "asc", "old", "oldest" -> Sort.OLDEST_FIRST;
                    case "desc", "new", "newest" -> Sort.NEWEST_FIRST;
                    default -> throw new ParamParseException("Unknown order: " + flagValue);
                };
            }
            case "nod", "nodefault" -> {
                if (flagValue == null || flagValue.isBlank()) {
                    throw new ParamParseException(
                            "Flag nod requires a value (comma-separated param aliases).");
                }
                for (String aliasRaw : flagValue.split(",")) {
                    String paramAlias = aliasRaw.trim();
                    if (paramAlias.isEmpty()) {
                        continue;
                    }
                    switch (paramAlias) {
                        case "r", "radius" -> state.defaultRadiusSuppressed = true;
                        case "t", "since", "time" -> state.sawTime = true;
                        default -> throw new ParamParseException(
                                "Flag nod: unknown default-bearing alias: " + paramAlias);
                    }
                }
            }
            default -> throw new ParamParseException("Unknown flag: " + name);
        }
    }

    /** Mutable box of state threaded through {@link #applyFlag}. */
    private static final class ParseState {
        final List<QueryPredicate> predicates = new ArrayList<>();
        final EnumSet<Flag> flags = EnumSet.noneOf(Flag.class);
        final Set<String> usedHandlerAliases = new LinkedHashSet<>();
        Sort sort = Sort.NEWEST_FIRST;
        boolean defaultRadiusSuppressed = false;
        boolean sawTime = false;
        boolean global = false;
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
