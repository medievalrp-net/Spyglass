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
        String[] tokens = value.split(",");
        List<UUID> ids = new ArrayList<>();
        List<String> rawNames = new ArrayList<>();
        for (String raw : tokens) {
            String trimmed = raw.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            UUID uuidLiteral = tryParseUuid(trimmed);
            if (uuidLiteral != null) {
                ids.add(uuidLiteral);
                continue;
            }
            UUID resolved = nameResolver.apply(trimmed);
            if (resolved != null) {
                ids.add(resolved);
                continue;
            }
            // Fallback: Bukkit never saw this player, but the name may still
            // exist verbatim in source.playerName for events captured while
            // the player was connected. Matches v1 behavior, which does a
            // raw string match.
            rawNames.add(trimmed);
        }
        if (ids.isEmpty() && rawNames.isEmpty()) {
            throw new ParamParseException("Player parameter requires at least one name.");
        }
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
                .filter(name -> name.toLowerCase().startsWith(input.toLowerCase()))
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

    @SuppressWarnings("deprecation")
    private static UUID resolveViaBukkit(String name) {
        var offline = Bukkit.getOfflinePlayer(name);
        if (offline == null || !offline.hasPlayedBefore() && Bukkit.getPlayerExact(name) == null) {
            var online = Bukkit.getPlayerExact(name);
            return online == null ? null : online.getUniqueId();
        }
        return offline.getUniqueId();
    }
}
