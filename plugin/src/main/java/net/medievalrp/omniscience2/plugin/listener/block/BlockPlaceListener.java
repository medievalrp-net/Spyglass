package net.medievalrp.omniscience2.plugin.listener.block;

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
import org.bukkit.event.block.BlockPlaceEvent;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class BlockPlaceListener implements RecordingListener {

    private final Recorder recorder;
    private final RecordingSupport support;

    public BlockPlaceListener(Recorder recorder, RecordingSupport support) {
        this.recorder = recorder;
        this.support = support;
    }

    @Override
    public Set<String> events() {
        return Set.of("place");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        BlockSnapshot before = BlockSnapshots.capture(event.getBlockReplacedState());
        BlockSnapshot after = BlockSnapshots.capture(event.getBlock().getState());
        BlockLocation location = BlockLocations.fromLocation(event.getBlock().getLocation());
        RecordContext ctx = support.playerContext(event.getPlayer(), location);
        recorder.record(BlockPlaceRecord.of(ctx, "place", after.material().name(), before, after));
    }
}
