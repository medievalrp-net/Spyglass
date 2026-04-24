package net.medievalrp.omniscience2.plugin.listener.environment;

import java.util.stream.Stream;
import net.medievalrp.omniscience2.api.event.BlockBreakRecord;
import net.medievalrp.omniscience2.api.event.BlockSnapshot;
import net.medievalrp.omniscience2.api.event.RecordContext;
import net.medievalrp.omniscience2.api.extension.EventExtractor;
import net.medievalrp.omniscience2.api.util.BlockLocation;
import net.medievalrp.omniscience2.plugin.listener.ExtractorSupport;
import net.medievalrp.omniscience2.plugin.util.BlockLocations;
import net.medievalrp.omniscience2.plugin.util.BlockSnapshots;
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
        RecordContext ctx = support.environmentContext("leaves-decay", location);
        return Stream.of(BlockBreakRecord.of(ctx, "decay", original.material().name(), original, after));
    }
}
