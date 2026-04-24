package net.medievalrp.spyglass.plugin.listener.modern;

import java.util.Set;
import net.medievalrp.spyglass.api.event.BlockPlaceRecord;
import net.medievalrp.spyglass.api.event.BlockSnapshot;
import net.medievalrp.spyglass.api.event.RecordContext;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.plugin.listener.RecordingSupport;
import net.medievalrp.spyglass.plugin.listener.RecordingListener;
import net.medievalrp.spyglass.plugin.pipeline.Recorder;
import net.medievalrp.spyglass.plugin.util.BlockLocations;
import net.medievalrp.spyglass.plugin.util.BlockSnapshots;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.SculkBloomEvent;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class SculkListener implements RecordingListener {

    private final Recorder recorder;
    private final RecordingSupport support;

    public SculkListener(Recorder recorder, RecordingSupport support) {
        this.recorder = recorder;
        this.support = support;
    }

    @Override
    public Set<String> events() {
        return Set.of("sculk");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSculkBloom(SculkBloomEvent event) {
        Block block = event.getBlock();
        BlockLocation location = BlockLocations.fromLocation(block.getLocation());
        BlockSnapshot snap = BlockSnapshots.of(block.getType(), block.getBlockData().getAsString());
        RecordContext ctx = support.context(
                support.environmentOrigin("sculk-bloom"),
                support.environmentSource("sculk:charge=" + event.getCharge()),
                location);
        recorder.record(BlockPlaceRecord.of(ctx, "sculk", block.getType().name(), snap, snap));
    }
}
