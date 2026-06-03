package net.medievalrp.spyglass.plugin.command.param;

import java.util.List;
import net.medievalrp.spyglass.api.param.ParamParseException;
import net.medievalrp.spyglass.api.param.QueryParamHandler;
import net.medievalrp.spyglass.api.query.QueryPredicate;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;

public final class WorldParam implements QueryParamHandler {

    @Override
    public List<String> aliases() {
        return List.of("w", "world");
    }

    @Override
    public QueryPredicate parse(String alias, String value, ParamContext context) throws ParamParseException {
        if (value == null || value.isBlank()) {
            throw new ParamParseException("World parameter requires a name.");
        }
        World world = Bukkit.getWorld(value);
        if (world == null) {
            throw new ParamParseException("Unknown world: " + value);
        }
        return new QueryPredicate.Eq("location.worldId", world.getUID());
    }

    @Override
    public boolean suppressesDefaultRadius(String alias) {
        return true;
    }

    @Override
    public List<String> suggestions(CommandSender sender, String input) {
        String lower = input.toLowerCase(java.util.Locale.ROOT);
        return Bukkit.getWorlds().stream()
                .map(World::getName)
                .filter(name -> name.toLowerCase(java.util.Locale.ROOT).startsWith(lower))
                .toList();
    }
}
