package net.medievalrp.spyglass.plugin.command.param;

import java.util.List;
import java.util.UUID;
import net.medievalrp.spyglass.api.param.ParamParseException;
import net.medievalrp.spyglass.api.param.QueryParamHandler;
import net.medievalrp.spyglass.api.query.QueryPredicate;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

public final class PlayerParam implements QueryParamHandler {

    @Override
    public List<String> aliases() {
        return List.of("p", "player");
    }

    @Override
    public QueryPredicate parse(String alias, String value, ParamContext context) throws ParamParseException {
        if (value == null || value.isBlank()) {
            throw new ParamParseException("Player parameter requires a name.");
        }
        String[] names = value.split(",");
        List<UUID> ids = new java.util.ArrayList<>();
        for (String raw : names) {
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
            throw new ParamParseException("Player parameter requires at least one name.");
        }
        if (ids.size() == 1) {
            return new QueryPredicate.Eq("source.playerId", ids.getFirst());
        }
        return new QueryPredicate.In("source.playerId", ids);
    }

    @Override
    public List<String> suggestions(CommandSender sender, String input) {
        return Bukkit.getOnlinePlayers().stream()
                .map(player -> player.getName())
                .filter(name -> name.toLowerCase().startsWith(input.toLowerCase()))
                .toList();
    }

    @SuppressWarnings("deprecation")
    private UUID resolve(String name) {
        var offline = Bukkit.getOfflinePlayer(name);
        if (offline == null || !offline.hasPlayedBefore() && Bukkit.getPlayerExact(name) == null) {
            var online = Bukkit.getPlayerExact(name);
            return online == null ? null : online.getUniqueId();
        }
        return offline.getUniqueId();
    }
}
