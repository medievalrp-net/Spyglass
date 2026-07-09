package net.medievalrp.spyglass.plugin.util;

import java.util.Optional;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

public final class BlockLocations {

    /**
     * Sentinel world id for a capture whose world was already gone (#232).
     * A stale {@link Location} - an entity observed mid world-unload, some
     * async contexts - can legitimately return a null world; before this,
     * every factory below NPE'd on the tick, on the high-frequency
     * break/place path. A sentinel beats both throwing (kills the capture
     * AND the listener) and returning null (a null location rippling into
     * a record is the #230 drain-poison failure). The record persists with
     * time/player/action intact; {@link #resolveWorld} finds nothing for
     * it, so rollback skips it.
     */
    private static final java.util.UUID NULL_WORLD_ID = new java.util.UUID(0L, 0L);

    private BlockLocations() {
    }

    public static net.medievalrp.spyglass.api.util.BlockLocation fromLocation(Location location) {
        World world = location.getWorld();
        if (world == null) {
            return worldless(location.getBlockX(), location.getBlockY(), location.getBlockZ());
        }
        return new net.medievalrp.spyglass.api.util.BlockLocation(
                world.getUID(),
                world.getName(),
                location.getBlockX(),
                location.getBlockY(),
                location.getBlockZ());
    }

    /**
     * Allocation-free overload for callers that already hold a {@link Block}.
     * {@code block.getLocation()} would allocate a throwaway {@link Location}
     * only to read its floored block coords; a block's own {@code getX/Y/Z}
     * already return those ints, so this skips the intermediate object on the
     * high-frequency break path. Equivalent to
     * {@code fromLocation(block.getLocation())}.
     */
    public static net.medievalrp.spyglass.api.util.BlockLocation fromBlock(Block block) {
        return from(block.getWorld(), block.getX(), block.getY(), block.getZ());
    }

    /**
     * Allocation-free overload for callers that already hold a {@link World}
     * and integer block coords (e.g. a cascade walking a column). Equivalent
     * to {@code fromLocation(new Location(world, x, y, z))} without the
     * {@link Location} allocation.
     */
    public static net.medievalrp.spyglass.api.util.BlockLocation from(World world, int x, int y, int z) {
        if (world == null) {
            return worldless(x, y, z);
        }
        return new net.medievalrp.spyglass.api.util.BlockLocation(
                world.getUID(),
                world.getName(),
                x,
                y,
                z);
    }

    private static net.medievalrp.spyglass.api.util.BlockLocation worldless(int x, int y, int z) {
        return new net.medievalrp.spyglass.api.util.BlockLocation(NULL_WORLD_ID, "", x, y, z);
    }

    public static Optional<World> resolveWorld(net.medievalrp.spyglass.api.util.BlockLocation location) {
        World world = Bukkit.getWorld(location.worldId());
        if (world != null) {
            return Optional.of(world);
        }
        return Optional.ofNullable(Bukkit.getWorld(location.worldName()));
    }

    public static Location toBlockCenter(net.medievalrp.spyglass.api.util.BlockLocation location) {
        World world = resolveWorld(location).orElse(null);
        if (world == null) {
            return null;
        }
        return new Location(world, location.x() + 0.5D, location.y(), location.z() + 0.5D);
    }
}
