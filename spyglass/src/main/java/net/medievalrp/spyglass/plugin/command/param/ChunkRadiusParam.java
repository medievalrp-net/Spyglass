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
 * Chunk based radius alternative to {@link RadiusParam} that selects chunk
 *
 * <p>{@code cr:1} selects the chunk the player is inside anything past {@code cr:N}
 * expands {@code N-1} chunks in every direction, so {@code cr:2} is the
 * 3x3 chunk. The Y is always the min and max of the world or minecrafts min and max
 * if world is not obtainable.
 *
 */
public final class ChunkRadiusParam implements QueryParamHandler {
    private static final int MIN_Y = -64;
    private static final int MAX_Y = 320;

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

        int minY = MIN_Y;
        int maxY = MAX_Y;
        World world = BlockLocations.resolveWorld(origin).orElse(null);
        if (world != null) {
            minY = world.getMinHeight();
            maxY = world.getMaxHeight() - 1;
        }
        return boxAround(origin, chunks, context.maxRadius(), minY, maxY);
    }

    public static QueryPredicate boxAround(BlockLocation origin, int chunks, int maxRadius, int minY, int maxY) {
        int cx = origin.x() >> 4;
        int cz = origin.z() >> 4;
        int expand = chunks - 1;
        int maxChunks = maxRadius / 16;
        expand = Math.min(expand, maxChunks);

        int minX = (cx - expand) << 4;
        int maxX = ((cx + expand) << 4) + 15;
        int minZ = (cz - expand) << 4;
        int maxZ = ((cz + expand) << 4) + 15;

        return new QueryPredicate.And(List.of(
                new QueryPredicate.Eq("location.worldId", origin.worldId()),
                new QueryPredicate.Range("location.x", minX, maxX),
                new QueryPredicate.Range("location.y", minY, maxY),
                new QueryPredicate.Range("location.z", minZ, maxZ)));
    }

    @Override
    public boolean suppressesDefaultRadius(String alias) {
        return true;
    }

    @Override
    public List<String> suggestions(CommandSender sender, String input) {
        if (input.isEmpty()) {
            return List.of("1", "2", "3", "5");
        }
        return List.of();
    }
}
