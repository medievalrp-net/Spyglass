package net.medievalrp.spyglass.plugin.listener.environment;

import java.util.Set;
import java.util.stream.Stream;
import net.medievalrp.spyglass.api.event.BlockBreakRecord;
import net.medievalrp.spyglass.api.event.BlockSnapshot;
import net.medievalrp.spyglass.api.event.RecordContext;
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
    public Set<String> events() {
        return Set.of("decay");
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
