package net.medievalrp.omniscience2.plugin.listener.environment;

import java.util.Set;
import net.medievalrp.omniscience2.api.event.BlockPlaceRecord;
import net.medievalrp.omniscience2.api.event.BlockSnapshot;
import net.medievalrp.omniscience2.api.event.RecordContext;
import net.medievalrp.omniscience2.api.util.BlockLocation;
import net.medievalrp.omniscience2.plugin.listener.RecordingSupport;
import net.medievalrp.omniscience2.plugin.listener.RecordingListener;
import net.medievalrp.omniscience2.plugin.pipeline.Recorder;
import net.medievalrp.omniscience2.plugin.util.BlockLocations;
import net.medievalrp.omniscience2.plugin.util.BlockSnapshots;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockGrowEvent;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class BlockGrowListener implements RecordingListener {

    private final Recorder recorder;
    private final RecordingSupport support;

    public BlockGrowListener(Recorder recorder, RecordingSupport support) {
        this.recorder = recorder;
        this.support = support;
    }

    @Override
    public Set<String> events() {
        return Set.of("grow");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockGrow(BlockGrowEvent event) {
        BlockSnapshot before = BlockSnapshots.capture(event.getBlock().getState());
        BlockSnapshot after = BlockSnapshots.capture(event.getNewState());
        BlockLocation location = BlockLocations.fromLocation(event.getBlock().getLocation());
        RecordContext ctx = support.environmentContext("block-grow", location);
        recorder.record(BlockPlaceRecord.of(ctx, "grow", after.material().name(), before, after));
    }
}
