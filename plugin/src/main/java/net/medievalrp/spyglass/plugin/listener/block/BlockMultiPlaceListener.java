package net.medievalrp.spyglass.plugin.listener.block;

import java.time.Instant;
import java.util.Set;
import net.medievalrp.spyglass.api.event.BlockPlaceRecord;
import net.medievalrp.spyglass.api.event.BlockSnapshot;
import net.medievalrp.spyglass.api.event.Origin;
import net.medievalrp.spyglass.api.event.Source;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.plugin.listener.RecordingSupport;
import net.medievalrp.spyglass.plugin.listener.RecordingListener;
import net.medievalrp.spyglass.plugin.pipeline.Recorder;
import net.medievalrp.spyglass.plugin.util.BlockLocations;
import net.medievalrp.spyglass.plugin.util.BlockSnapshots;
import org.bukkit.block.BlockState;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class BlockMultiPlaceListener implements RecordingListener {

    private final Recorder recorder;
    private final RecordingSupport support;

    public BlockMultiPlaceListener(Recorder recorder, RecordingSupport support) {
        this.recorder = recorder;
        this.support = support;
    }

    @Override
    public Set<String> events() {
        return Set.of("place");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockMultiPlace(BlockMultiPlaceEvent event) {
        Instant occurred = support.now();
        Origin origin = support.playerOrigin();
        Source source = support.playerSource(event.getPlayer());
        for (BlockState state : event.getReplacedBlockStates()) {
            recorder.record(toRecord(state, occurred, origin, source));
        }
    }

    private BlockPlaceRecord toRecord(BlockState replaced, Instant occurred, Origin origin, Source source) {
        BlockSnapshot before = BlockSnapshots.capture(replaced);
        BlockSnapshot after = BlockSnapshots.capture(replaced.getBlock().getState());
        BlockLocation location = BlockLocations.fromLocation(replaced.getLocation());
        return new BlockPlaceRecord(
                support.newId(),
                1,
                "place",
                occurred,
                support.expiresAt(occurred),
                origin,
                source,
                location,
                after.material().name(),
                before,
                after);
    }
}
