package net.medievalrp.omniscience2.plugin.listener.environment;

import java.util.Set;
import java.util.stream.Stream;
import net.medievalrp.omniscience2.api.event.BlockPlaceRecord;
import net.medievalrp.omniscience2.api.event.BlockSnapshot;
import net.medievalrp.omniscience2.api.event.RecordContext;
import net.medievalrp.omniscience2.api.extension.EventExtractor;
import net.medievalrp.omniscience2.api.util.BlockLocation;
import net.medievalrp.omniscience2.plugin.listener.ExtractorSupport;
import net.medievalrp.omniscience2.plugin.util.BlockLocations;
import net.medievalrp.omniscience2.plugin.util.BlockSnapshots;
import org.bukkit.block.BlockState;
import org.bukkit.event.world.StructureGrowEvent;

public final class StructureGrowExtractor implements EventExtractor<StructureGrowEvent, BlockPlaceRecord> {

    private final ExtractorSupport support;

    public StructureGrowExtractor(ExtractorSupport support) {
        this.support = support;
    }

    @Override
    public Class<StructureGrowEvent> eventType() {
        return StructureGrowEvent.class;
    }

    @Override
    public Set<String> events() {
        return Set.of("grow");
    }

    @Override
    public Stream<BlockPlaceRecord> extract(StructureGrowEvent event) {
        String species = event.getSpecies().name();
        String who = event.getPlayer() == null ? "structure-grow" : event.getPlayer().getName();
        return event.getBlocks().stream().map(state -> fromState(state, species, who));
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
