package net.medievalrp.spyglass.plugin.command.param;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.medievalrp.spyglass.api.param.ParamParseException;
import net.medievalrp.spyglass.api.param.QueryParamHandler;
import net.medievalrp.spyglass.api.query.QueryPredicate;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

/**
 * {@code rcp:Alice} or {@code rcp:Alice,Bob} — match chat records
 * where any of the given players appear in
 * {@link net.medievalrp.spyglass.api.event.ChatRecord#recipients()}.
 * Resolves player names to UUIDs via Bukkit's offline-player cache.
 *
 * <p>Storage-wise {@code recipients} is a {@code List<UUID>}; Mongo's
 * match-any-array-element semantics make {@code Eq} / {@code In}
 * transparently work on the array field.
 */
public final class RecipientParam implements QueryParamHandler {

    @Override
    public List<String> aliases() {
        return List.of("rcp", "recipient");
    }

    @Override
    public QueryPredicate parse(String alias, String value, ParamContext context) throws ParamParseException {
        if (value == null || value.isBlank()) {
            throw new ParamParseException("rcp requires a player name.");
        }
        List<UUID> ids = new ArrayList<>();
        for (String raw : value.split(",")) {
            String trimmed = raw.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            UUID id = resolve(trimmed);
            if (id == null) {
                throw new ParamParseException("Unknown player: " + trimmed);
            }
            ids.add(id);
        }
        if (ids.isEmpty()) {
            throw new ParamParseException("rcp requires at least one player.");
        }
        if (ids.size() == 1) {
            return new QueryPredicate.Eq("recipients", ids.getFirst());
        }
        return new QueryPredicate.In("recipients", ids);
    }

    @Override
    public List<String> suggestions(CommandSender sender, String input) {
        String lower = input.toLowerCase(java.util.Locale.ROOT);
        return Bukkit.getOnlinePlayers().stream()
                .map(p -> p.getName())
                .filter(name -> name.toLowerCase(java.util.Locale.ROOT).startsWith(lower))
                .toList();
    }

    @SuppressWarnings("deprecation")
    private static UUID resolve(String name) {
        var online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online.getUniqueId();
        }
        var offline = Bukkit.getOfflinePlayer(name);
        if (offline != null && offline.hasPlayedBefore()) {
            return offline.getUniqueId();
        }
        return null;
    }
}
