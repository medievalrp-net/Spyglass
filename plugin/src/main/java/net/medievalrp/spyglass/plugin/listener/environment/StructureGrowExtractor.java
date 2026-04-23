package net.medievalrp.spyglass.plugin.listener.environment;

import java.time.Instant;
import java.util.stream.Stream;
import net.medievalrp.spyglass.api.event.BlockPlaceRecord;
import net.medievalrp.spyglass.api.event.BlockSnapshot;
import net.medievalrp.spyglass.api.extension.EventExtractor;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.plugin.listener.ExtractorSupport;
import net.medievalrp.spyglass.plugin.util.BlockLocations;
import net.medievalrp.spyglass.plugin.util.BlockSnapshots;
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
    public Stream<BlockPlaceRecord> extract(StructureGrowEvent event) {
        Instant occurred = support.now();
        String origin = event.getSpecies().name();
        String who = event.getPlayer() == null ? "structure-grow" : event.getPlayer().getName();
        return event.getBlocks().stream().map(state -> fromState(state, occurred, origin, who));
    }

    private BlockPlaceRecord fromState(BlockState state, Instant occurred, String species, String detail) {
        BlockSnapshot before = BlockSnapshots.air();
        BlockSnapshot after = BlockSnapshots.capture(state);
        BlockLocation location = BlockLocations.fromLocation(state.getLocation());
        return new BlockPlaceRecord(
                support.newId(),
                1,
                "grow",
                occurred,
                support.expiresAt(occurred),
                support.environmentOrigin("structure-grow:" + species),
                support.environmentSource("structure-grow:" + detail),
                location,
                after.material().name(),
                before,
                after);
    }
}
