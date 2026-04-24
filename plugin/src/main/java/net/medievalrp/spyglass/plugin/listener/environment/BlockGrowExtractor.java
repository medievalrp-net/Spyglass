package net.medievalrp.spyglass.plugin.listener.environment;

import java.util.Set;
import java.util.stream.Stream;
import net.medievalrp.spyglass.api.event.BlockPlaceRecord;
import net.medievalrp.spyglass.api.event.BlockSnapshot;
import net.medievalrp.spyglass.api.event.RecordContext;
import net.medievalrp.spyglass.api.extension.EventExtractor;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.plugin.listener.ExtractorSupport;
import net.medievalrp.spyglass.plugin.util.BlockLocations;
import net.medievalrp.spyglass.plugin.util.BlockSnapshots;
import org.bukkit.event.block.BlockGrowEvent;

public final class BlockGrowExtractor implements EventExtractor<BlockGrowEvent, BlockPlaceRecord> {

    private final ExtractorSupport support;

    public BlockGrowExtractor(ExtractorSupport support) {
        this.support = support;
    }

    @Override
    public Class<BlockGrowEvent> eventType() {
        return BlockGrowEvent.class;
    }

    @Override
    public Set<String> events() {
        return Set.of("grow");
    }

    @Override
    public Stream<BlockPlaceRecord> extract(BlockGrowEvent event) {
        BlockSnapshot before = BlockSnapshots.capture(event.getBlock().getState());
        BlockSnapshot after = BlockSnapshots.capture(event.getNewState());
        BlockLocation location = BlockLocations.fromLocation(event.getBlock().getLocation());
        RecordContext ctx = support.environmentContext("block-grow", location);
        return Stream.of(BlockPlaceRecord.of(ctx, "grow", after.material().name(), before, after));
    }
}
