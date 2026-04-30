package net.medievalrp.spyglass.plugin.rollback;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.entity.FallingBlock;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityChangeBlockEvent;

/**
 * Suppresses {@link FallingBlock} transitions inside an active rollback
 * region while the rollback is writing.
 *
 * <p>Why we need this: when the rollback restores a sand/gravel/concrete-
 * powder block via {@link org.bukkit.block.Block#setBlockData}, the block
 * place still fires {@code Block.onPlace}, which schedules a gravity
 * tick on the next world tick. That tick converts the just-placed block
 * into a {@code FallingBlock} entity and drops it. Visible bug: every
 * sand block in a restored cube falls one tick after the rollback finishes.
 *
 * <p>The earlier rev avoided this by writing the palette entry directly
 * into {@code LevelChunkSection} via NMS reflection (skipping {@code
 * onPlace} entirely). That coupled us to Paper internals and to Mojang
 * mappings. This listener is the no-NMS replacement: let {@code onPlace}
 * fire, let the gravity tick schedule, then cancel the resulting
 * {@link EntityChangeBlockEvent} when it tries to convert the block to a
 * falling-block entity inside an active rollback region.
 *
 * <p>Active regions are registered via {@link #enter} and removed via
 * {@link #exit}; the handle returned by {@code enter} must be passed to
 * {@code exit} (typically in a {@code whenComplete} callback). Multiple
 * concurrent rollbacks register independent regions.
 *
 * <p>Performance: the event fires only when a falling-capable block
 * actually decides to fall (i.e. has air below it on the next tick).
 * For a typical rollback that restores a stone cube, this fires zero
 * times. For a restored sand pile with eroded sides, it fires once per
 * sand block that lands on air — at most O(restored sand blocks),
 * never per-block of the rollback.
 */
public final class RollbackPhysicsBlocker implements Listener {

    private final ConcurrentHashMap<Long, RollbackRegion> activeRegions = new ConcurrentHashMap<>();
    private final AtomicLong nextHandle = new AtomicLong();

    /**
     * Mark a region as "rollback in progress." Returns a handle the
     * caller passes to {@link #exit} when the rollback finishes (or
     * fails).
     */
    public long enter(UUID worldId,
                      int minX, int minY, int minZ,
                      int maxX, int maxY, int maxZ) {
        long handle = nextHandle.getAndIncrement();
        activeRegions.put(handle, new RollbackRegion(
                worldId, minX, minY, minZ, maxX, maxY, maxZ));
        return handle;
    }

    /** Release the region marked by {@code handle}. Idempotent. */
    public void exit(long handle) {
        activeRegions.remove(handle);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (!(event.getEntity() instanceof FallingBlock)) {
            return;
        }
        if (activeRegions.isEmpty()) {
            return;
        }
        UUID worldId = event.getBlock().getWorld().getUID();
        int x = event.getBlock().getX();
        int y = event.getBlock().getY();
        int z = event.getBlock().getZ();
        for (RollbackRegion region : activeRegions.values()) {
            if (region.contains(worldId, x, y, z)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    private record RollbackRegion(UUID worldId,
                                  int minX, int minY, int minZ,
                                  int maxX, int maxY, int maxZ) {
        boolean contains(UUID world, int x, int y, int z) {
            return worldId.equals(world)
                    && x >= minX && x <= maxX
                    && y >= minY && y <= maxY
                    && z >= minZ && z <= maxZ;
        }
    }
}
