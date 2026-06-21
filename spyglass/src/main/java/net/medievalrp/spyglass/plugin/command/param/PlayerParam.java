package net.medievalrp.spyglass.plugin.command.param;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import net.medievalrp.spyglass.api.param.ParamParseException;
import net.medievalrp.spyglass.api.param.QueryParamHandler;
import net.medievalrp.spyglass.api.query.QueryPredicate;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

public final class PlayerParam implements QueryParamHandler {

    private final Function<String, UUID> nameResolver;

    public PlayerParam() {
        this(PlayerParam::resolveViaBukkit);
    }

    // Package-private ctor used by unit tests to inject a deterministic
    // resolver so the fallback path can be exercised without a live server.
    PlayerParam(Function<String, UUID> nameResolver) {
        this.nameResolver = nameResolver;
    }

    @Override
    public List<String> aliases() {
        return List.of("p", "player");
    }

    @Override
    public QueryPredicate parse(String alias, String value, ParamContext context) throws ParamParseException {
        if (value == null || value.isBlank()) {
            throw new ParamParseException("Player parameter requires a name.");
        }
        // `!`-prefixed names are excludes (#30), same syntax as c:.
        List<UUID> ids = new ArrayList<>();
        List<String> rawNames = new ArrayList<>();
        List<UUID> excludeIds = new ArrayList<>();
        List<String> excludeRawNames = new ArrayList<>();
        for (String raw : value.split(",")) {
            String trimmed = raw.trim();
            if (trimmed.isEmpty() || trimmed.equals("!")) {
                continue;
            }
            boolean negated = trimmed.startsWith("!");
            String name = negated ? trimmed.substring(1) : trimmed;
            List<UUID> idSink = negated ? excludeIds : ids;
            List<String> nameSink = negated ? excludeRawNames : rawNames;
            UUID uuidLiteral = tryParseUuid(name);
            if (uuidLiteral != null) {
                idSink.add(uuidLiteral);
                continue;
            }
            UUID resolved = nameResolver.apply(name);
            if (resolved != null) {
                idSink.add(resolved);
                continue;
            }
            // Fallback: Bukkit never saw this player, but the name may still
            // exist verbatim in source.playerName for events captured while
            // the player was connected. Matches v1 behavior, which does a
            // raw string match.
            nameSink.add(name);
        }
        QueryPredicate include = matchPredicate(ids, rawNames);
        QueryPredicate exclude = matchPredicate(excludeIds, excludeRawNames);
        if (include == null && exclude == null) {
            throw new ParamParseException("Player parameter requires at least one name.");
        }
        List<QueryPredicate> clauses = new ArrayList<>(2);
        if (include != null) {
            clauses.add(include);
        }
        if (exclude != null) {
            clauses.add(new QueryPredicate.Not(exclude));
        }
        return clauses.size() == 1 ? clauses.getFirst() : new QueryPredicate.And(clauses);
    }

    private static QueryPredicate matchPredicate(List<UUID> ids, List<String> rawNames) {
        QueryPredicate byId = idPredicate(ids);
        QueryPredicate byName = namePredicate(rawNames);
        if (byId != null && byName != null) {
            return new QueryPredicate.Or(List.of(byId, byName));
        }
        return byId != null ? byId : byName;
    }

    @Override
    public List<String> suggestions(CommandSender sender, String input) {
        return Bukkit.getOnlinePlayers().stream()
                .map(player -> player.getName())
                .filter(name -> name.toLowerCase(java.util.Locale.ROOT).startsWith(input.toLowerCase(java.util.Locale.ROOT)))
                .toList();
    }

    private static UUID tryParseUuid(String value) {
        // UUID.fromString is strict about hyphens. Only delegate if the
        // input looks structurally like a UUID to avoid catching names
        // that happen to contain hex characters.
        if (value.length() != 36 || value.charAt(8) != '-' || value.charAt(13) != '-'
                || value.charAt(18) != '-' || value.charAt(23) != '-') {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static QueryPredicate idPredicate(List<UUID> ids) {
        if (ids.isEmpty()) {
            return null;
        }
        if (ids.size() == 1) {
            return new QueryPredicate.Eq("source.playerId", ids.getFirst());
        }
        return new QueryPredicate.In("source.playerId", ids);
    }

    private static QueryPredicate namePredicate(List<String> names) {
        if (names.isEmpty()) {
            return null;
        }
        if (names.size() == 1) {
            return new QueryPredicate.Eq("source.playerName", names.getFirst());
        }
        return new QueryPredicate.In("source.playerName", names);
    }

    private static UUID resolveViaBukkit(String name) {
        // Prefer an exact online match (the common case: staff investigating
        // someone currently connected), then the local user cache.
        //
        // NEVER call Bukkit.getOfflinePlayer(String) here. That overload does
        // a BLOCKING HTTP lookup to Mojang's profile API when the name isn't
        // already cached, and commands run on the main server thread (the
        // simple execution coordinator dispatches handlers inline), so a cache
        // miss stalls the whole server for the round-trip - seconds, on a
        // Mojang slowdown. Spark caught exactly this (PlayerParam.parse ->
        // getOfflinePlayer -> HttpURLConnection on "Server thread").
        //
        // The Mojang round-trip also buys nothing for the query: source.playerId
        // only ever holds UUIDs of players who acted on THIS server, so a name
        // resolved for someone who never joined can't match a record. On a cache
        // miss we return null and the caller falls back to a verbatim
        // source.playerName match, which is exactly what we want.
        var online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online.getUniqueId();
        }
        var cached = Bukkit.getOfflinePlayerIfCached(name);
        return cached == null ? null : cached.getUniqueId();
    }
}
