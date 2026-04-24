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
import org.bukkit.block.BlockState;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.world.StructureGrowEvent;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class StructureGrowListener implements RecordingListener {

    private final Recorder recorder;
    private final RecordingSupport support;

    public StructureGrowListener(Recorder recorder, RecordingSupport support) {
        this.recorder = recorder;
        this.support = support;
    }

    @Override
    public Set<String> events() {
        return Set.of("grow");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onStructureGrow(StructureGrowEvent event) {
        String species = event.getSpecies().name();
        String who = event.getPlayer() == null ? "structure-grow" : event.getPlayer().getName();
        for (BlockState state : event.getBlocks()) {
            recorder.record(fromState(state, species, who));
        }
    }

    private BlockPlaceRecord fromState(BlockState state, String species, String detail) {
        BlockSnapshot before = BlockSnapshots.air();
        BlockSnapshot after = BlockSnapshots.capture(state);
        BlockLocation location = BlockLocations.fromLocation(state.getLocation());
        RecordContext ctx = support.context(
                support.environmentOrigin("structure-grow:" + species),
                support.environmentSource("structure-grow:" + detail),
                location);
        return BlockPlaceRecord.of(ctx, "grow", after.material().name(), before, after);
    }
}
