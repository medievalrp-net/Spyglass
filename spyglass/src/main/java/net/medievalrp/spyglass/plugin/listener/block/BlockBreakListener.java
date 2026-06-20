package net.medievalrp.spyglass.plugin.listener.block;

import java.util.Set;
import net.medievalrp.spyglass.api.event.BlockBreakRecord;
import net.medievalrp.spyglass.api.event.BlockSnapshot;
import net.medievalrp.spyglass.api.event.RecordContext;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.plugin.listener.RecordingSupport;
import net.medievalrp.spyglass.plugin.listener.RecordingListener;
import net.medievalrp.spyglass.plugin.pipeline.Recorder;
import net.medievalrp.spyglass.plugin.util.BlockLocations;
import net.medievalrp.spyglass.plugin.util.BlockSnapshots;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class BlockBreakListener implements RecordingListener {

    private final Recorder recorder;
    private final RecordingSupport support;

    public BlockBreakListener(Recorder recorder, RecordingSupport support) {
        this.recorder = recorder;
        this.support = support;
    }

    @Override
    public Set<String> events() {
        return Set.of("break");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        BlockSnapshot original = BlockSnapshots.capture(event.getBlock().getState());
        BlockSnapshot after = BlockSnapshots.air();
        BlockLocation location = BlockLocations.fromBlock(event.getBlock());
        RecordContext ctx = support.playerContext(event.getPlayer(), location);
        recorder.record(BlockBreakRecord.of(ctx, "break", original.material().name(), original, after));
        // Cascade: if breaking this block knocks out the support of a
        // gravity-affected column above (sand, gravel, anvil, concrete
        // powder, etc.), pre-emptively log the column's breaks tagged
        // to the same player. Without this, the column drops as
        // falling-block entities, the original cells aren't recorded
        // as "broken by player", and a /spyglass rollback p:them
        // restores the support but the sand stays gone.
        net.medievalrp.spyglass.plugin.util.FallingBlockCascade.emitCascadeAbove(
                recorder, support, event.getPlayer(),
                event.getBlock().getWorld(),
                event.getBlock().getX(), event.getBlock().getY(), event.getBlock().getZ());
    }
}
