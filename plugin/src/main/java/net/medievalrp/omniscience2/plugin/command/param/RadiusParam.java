package net.medievalrp.omniscience2.plugin.command.param;

import java.util.List;
import net.medievalrp.omniscience2.api.param.ParamParseException;
import net.medievalrp.omniscience2.api.param.QueryParamHandler;
import net.medievalrp.omniscience2.api.query.QueryPredicate;
import net.medievalrp.omniscience2.api.util.BlockLocation;
import org.bukkit.command.CommandSender;

public final class RadiusParam implements QueryParamHandler {

    @Override
    public List<String> aliases() {
        return List.of("r", "radius");
    }

    @Override
    public QueryPredicate parse(String alias, String value, ParamContext context) throws ParamParseException {
        BlockLocation origin = context.senderLocation();
        if (origin == null) {
            throw new ParamParseException("Radius requires a located sender.");
        }
        int radius;
        try {
            radius = Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            throw new ParamParseException("Radius must be a number.", ex);
        }
        if (radius <= 0) {
            throw new ParamParseException("Radius must be positive.");
        }
        int clamped = Math.min(radius, context.maxRadius());
        return groupAround(origin, clamped);
    }

    public static QueryPredicate groupAround(BlockLocation origin, int radius) {
        return new QueryPredicate.And(List.of(
                new QueryPredicate.Eq("location.worldId", origin.worldId()),
                new QueryPredicate.Range("location.x", origin.x() - radius, origin.x() + radius),
                new QueryPredicate.Range("location.y", origin.y() - radius, origin.y() + radius),
                new QueryPredicate.Range("location.z", origin.z() - radius, origin.z() + radius)));
    }

    @Override
    public boolean suppressesDefaultRadius(String alias) {
        return true;
    }

    @Override
    public List<String> suggestions(CommandSender sender, String input) {
        if (input.isEmpty()) {
            return List.of("5", "10", "25", "50");
        }
        return List.of();
    }
}
