package net.medievalrp.spyglass.plugin.command.param;

import java.util.List;
import net.medievalrp.spyglass.api.param.ParamParseException;
import net.medievalrp.spyglass.api.param.QueryParamHandler;
import net.medievalrp.spyglass.api.query.QueryPredicate;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.plugin.util.BlockLocations;
import org.bukkit.World;
import org.bukkit.command.CommandSender;

/**
 * Chunk-based counterpart to {@link RadiusParam}. {@code cr:1} matches the
 * chunk the sender stands in; {@code cr:N} widens the box by {@code N-1}
 * chunks in every horizontal direction, so {@code cr:2} is 3x3 and {@code cr:3}
 * is 5x5. The box always spans the full world height, and its reach is capped
 * at {@code maxRadius / 16} chunks so a large value can't scan the whole world.
 */
public final class ChunkRadiusParam implements QueryParamHandler {
    // Height bounds used only when the sender's world can't be resolved (standard overworld).
    private static final int FALLBACK_MIN_Y = -64;
    private static final int FALLBACK_MAX_Y = 319;

    @Override
    public List<String> aliases() {
        return List.of("cr", "chunkradius");
    }

    @Override
    public QueryPredicate parse(String alias, String value, ParamContext context) throws ParamParseException {
        BlockLocation origin = context.senderLocation();
        if (origin == null) {
            throw new ParamParseException("Chunk radius requires a located sender.");
        }
        int chunks;
        try {
            chunks = Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            throw new ParamParseException("Chunk radius must be a number.", ex);
        }
        if (chunks <= 0) {
            throw new ParamParseException("Chunk radius must be positive.");
        }

        World world = BlockLocations.resolveWorld(origin).orElse(null);
        int minY = world != null ? world.getMinHeight() : FALLBACK_MIN_Y;
        int maxY = world != null ? world.getMaxHeight() - 1 : FALLBACK_MAX_Y;
        return boxAround(origin, chunks, context.maxRadius(), minY, maxY);
    }

    public static QueryPredicate boxAround(BlockLocation origin, int chunks, int maxRadius, int minY, int maxY) {
        int expand = Math.min(chunks - 1, maxRadius / 16);
        int cx = origin.x() >> 4;
        int cz = origin.z() >> 4;
        return new QueryPredicate.And(List.of(
                new QueryPredicate.Eq("location.worldId", origin.worldId()),
                new QueryPredicate.Range("location.x", (cx - expand) << 4, ((cx + expand) << 4) + 15),
                new QueryPredicate.Range("location.y", minY, maxY),
                new QueryPredicate.Range("location.z", (cz - expand) << 4, ((cz + expand) << 4) + 15)));
    }

    @Override
    public boolean suppressesDefaultRadius(String alias) {
        return true;
    }

    @Override
    public List<String> suggestions(CommandSender sender, String input) {
        return input.isEmpty() ? List.of("1", "2", "3", "5") : List.of();
    }
}
