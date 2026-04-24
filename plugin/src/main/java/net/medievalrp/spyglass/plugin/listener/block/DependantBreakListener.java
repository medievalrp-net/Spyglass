package net.medievalrp.spyglass.plugin.listener.block;

import java.util.Set;
import net.medievalrp.spyglass.api.event.BlockBreakRecord;
import net.medievalrp.spyglass.api.event.BlockSnapshot;
import net.medievalrp.spyglass.api.event.RecordContext;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.plugin.listener.RecordingListener;
import net.medievalrp.spyglass.plugin.listener.RecordingSupport;
import net.medievalrp.spyglass.plugin.pipeline.Recorder;
import net.medievalrp.spyglass.plugin.util.BlockDependents;
import net.medievalrp.spyglass.plugin.util.BlockLocations;
import net.medievalrp.spyglass.plugin.util.BlockSnapshots;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.jetbrains.annotations.ApiStatus;

/**
 * When a player breaks a block, find every dependent-style neighbor
 * (wall torches, carpets, levers, signs, rails, etc.) and emit a
 * companion break record for each. Without this, a rollback of the
 * host block leaves the attachments floating, and restore misses them
 * entirely because they were never recorded.
 *
 * <p>Two-block-tall / symmetric pairs (beds, doors, tall plants) are
 * covered by {@link MultiBlockBreakListener}; this one handles every
 * other attachment style via the {@link BlockDependents} taxonomy.
 */
@ApiStatus.Internal
public final class DependantBreakListener implements RecordingListener {

    private final Recorder recorder;
    private final RecordingSupport support;

    public DependantBreakListener(Recorder recorder, RecordingSupport support) {
        this.recorder = recorder;
        this.support = support;
    }

    @Override
    public Set<String> events() {
        return Set.of("break");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Block broken = event.getBlock();
        for (Block dependent : BlockDependents.collectDependents(broken)) {
            BlockSnapshot original = BlockSnapshots.capture(dependent.getState());
            BlockSnapshot after = BlockSnapshots.air();
            BlockLocation location = BlockLocations.fromLocation(dependent.getLocation());
            RecordContext ctx = support.playerContext(event.getPlayer(), location);
            recorder.record(BlockBreakRecord.of(ctx, "break", original.material().name(), original, after));
        }
    }
}
