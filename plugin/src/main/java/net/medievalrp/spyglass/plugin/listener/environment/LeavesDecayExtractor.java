package net.medievalrp.spyglass.plugin.listener.environment;

import java.time.Instant;
import java.util.stream.Stream;
import net.medievalrp.spyglass.api.event.BlockBreakRecord;
import net.medievalrp.spyglass.api.event.BlockSnapshot;
import net.medievalrp.spyglass.api.extension.EventExtractor;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.plugin.listener.ExtractorSupport;
import net.medievalrp.spyglass.plugin.util.BlockLocations;
import net.medievalrp.spyglass.plugin.util.BlockSnapshots;
import org.bukkit.event.block.LeavesDecayEvent;

public final class LeavesDecayExtractor implements EventExtractor<LeavesDecayEvent, BlockBreakRecord> {

    private final ExtractorSupport support;

    public LeavesDecayExtractor(ExtractorSupport support) {
        this.support = support;
    }

    @Override
    public Class<LeavesDecayEvent> eventType() {
        return LeavesDecayEvent.class;
    }

    @Override
    public Stream<BlockBreakRecord> extract(LeavesDecayEvent event) {
        BlockSnapshot original = BlockSnapshots.capture(event.getBlock().getState());
        BlockSnapshot after = BlockSnapshots.air();
        BlockLocation location = BlockLocations.fromLocation(event.getBlock().getLocation());
        Instant occurred = support.now();
        return Stream.of(new BlockBreakRecord(
                support.newId(),
                1,
                "decay",
                occurred,
                support.expiresAt(occurred),
                support.environmentOrigin("leaves-decay"),
                support.environmentSource("leaves-decay"),
                location,
                original.material().name(),
                original,
                after));
    }
}
