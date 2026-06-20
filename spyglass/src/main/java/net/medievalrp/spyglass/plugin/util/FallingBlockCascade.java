package net.medievalrp.spyglass.plugin.util;

import net.medievalrp.spyglass.api.event.BlockBreakRecord;
import net.medievalrp.spyglass.api.event.BlockSnapshot;
import net.medievalrp.spyglass.api.event.RecordContext;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.plugin.listener.RecordingSupport;
import net.medievalrp.spyglass.plugin.pipeline.Recorder;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;

/**
 * Predictively log breaks for falling-block-type blocks (sand, gravel,
 * anvil, concrete powder, dragon egg, …) when their support is removed.
 * Without this, a {@code //set 0} that knocks out the bottom of a sand
 * column drops the column as falling-block entities — those land or
 * shatter into items, the original cells become un-rollback-able
 * because no listener captured the gravity-cascade falls, and a later
 * rollback of the {@code //set} restores only the support layer.
 *
 * <p>The cascade is captured at the point of the human-attributable
 * break (in {@link
 * net.medievalrp.spyglass.plugin.worldedit.WorldEditSubscriber} and
 * {@link
 * net.medievalrp.spyglass.plugin.listener.block.BlockBreakListener})
 * by walking up the column from the broken cell, recording one extra
 * break per falling-block-type block until we hit a non-falling-block.
 * Each cascade break is tagged with the same player/origin as the
 * trigger so a {@code /spyglass rollback p:player} pulls them in
 * together.
 */
@ApiStatus.Internal
public final class FallingBlockCascade {

    /** Hard cap to keep a freak vertical sand silo from bloating one
     *  break event into thousands of cascade rows. ~world height — if
     *  someone has a 256-block sand column, we still log it all. */
    private static final int MAX_DEPTH = 320;

    private FallingBlockCascade() {
    }

    /** Walk from {@code (x, y+1, z)} upward, logging a break record
     *  for every gravity-affected block until we hit a block that
     *  wouldn't fall. The record's context (player + timestamp +
     *  origin) is shared with the trigger break so a rollback of the
     *  trigger naturally pulls the cascade in too.
     *
     *  <p>{@code alreadyCascaded} (nullable) is a set of packed
     *  {@code (y << 56 | x << 28 | z)} coordinates the caller has
     *  already cascade-emitted in this session; prevents an N²
     *  blow-up when WE breaks every cell of an N-tall sand column
     *  in the same op (each break would otherwise re-cascade the
     *  remaining N-y cells above it). Pass {@code null} for one-shot
     *  usage where the column is broken at most once. */
    public static void emitCascadeAbove(Recorder recorder,
                                        RecordingSupport support,
                                        Player player,
                                        World world,
                                        int x, int yStart, int z,
                                        java.util.Set<Long> alreadyCascaded) {
        for (int y = yStart + 1, depth = 0; depth < MAX_DEPTH; y++, depth++) {
            if (y >= world.getMaxHeight()) {
                return;
            }
            // Stop walking once we hit a non-gravity-affected block —
            // anything above it has support and won't fall.
            Block above = world.getBlockAt(x, y, z);
            if (!isGravityAffected(above.getType())) {
                return;
            }
            // Dedup: if another cascade in this session already logged
            // this exact cell, skip but keep walking — there might be
            // un-logged gravity blocks further up.
            if (alreadyCascaded != null) {
                long packed = ((long) y << 56) | ((long) (x & 0xFFFFFFF) << 28) | (z & 0xFFFFFFF);
                if (!alreadyCascaded.add(packed)) {
                    continue;
                }
            }
            BlockState state = above.getState();
            BlockSnapshot original = BlockSnapshots.capture(state);
            BlockSnapshot after = BlockSnapshots.air();
            BlockLocation location = BlockLocations.from(world, x, y, z);
            RecordContext ctx = support.playerContext(player, location);
            recorder.record(BlockBreakRecord.of(
                    ctx, "break", original.material().name(), original, after));
            // Register this cell with the landing-tracker so when its
            // falling-block entity lands somewhere, FallingBlockLandListener
            // can attribute the landing place back to the same player.
            // Without this, the cascade restores the original column on
            // rollback but the landed sand at the bottom stays orphaned.
            FallingBlockTracker.track(world.getUID(), x, y, z,
                    player.getUniqueId(), player.getName());
        }
    }

    /** Convenience overload for callers that don't need session-level
     *  dedup (single block break, not a bulk WE op). */
    public static void emitCascadeAbove(Recorder recorder,
                                        RecordingSupport support,
                                        Player player,
                                        World world,
                                        int x, int yStart, int z) {
        emitCascadeAbove(recorder, support, player, world, x, yStart, z, null);
    }

    /** {@code true} for blocks that fall when their support is removed.
     *  Update if Mojang adds new gravity-affected materials in a 1.21+
     *  release — the list is small and stable. */
    public static boolean isGravityAffected(Material m) {
        return m == Material.SAND
                || m == Material.RED_SAND
                || m == Material.SUSPICIOUS_SAND
                || m == Material.GRAVEL
                || m == Material.SUSPICIOUS_GRAVEL
                || m == Material.ANVIL
                || m == Material.CHIPPED_ANVIL
                || m == Material.DAMAGED_ANVIL
                || m == Material.DRAGON_EGG
                || m == Material.WHITE_CONCRETE_POWDER
                || m == Material.ORANGE_CONCRETE_POWDER
                || m == Material.MAGENTA_CONCRETE_POWDER
                || m == Material.LIGHT_BLUE_CONCRETE_POWDER
                || m == Material.YELLOW_CONCRETE_POWDER
                || m == Material.LIME_CONCRETE_POWDER
                || m == Material.PINK_CONCRETE_POWDER
                || m == Material.GRAY_CONCRETE_POWDER
                || m == Material.LIGHT_GRAY_CONCRETE_POWDER
                || m == Material.CYAN_CONCRETE_POWDER
                || m == Material.PURPLE_CONCRETE_POWDER
                || m == Material.BLUE_CONCRETE_POWDER
                || m == Material.BROWN_CONCRETE_POWDER
                || m == Material.GREEN_CONCRETE_POWDER
                || m == Material.RED_CONCRETE_POWDER
                || m == Material.BLACK_CONCRETE_POWDER
                || m == Material.POINTED_DRIPSTONE
                || m == Material.SCAFFOLDING;
    }
}
