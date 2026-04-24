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
import org.bukkit.event.block.BlockFormEvent;

public final class BlockFormExtractor implements EventExtractor<BlockFormEvent, BlockPlaceRecord> {

    private final ExtractorSupport support;

    public BlockFormExtractor(ExtractorSupport support) {
        this.support = support;
    }

    @Override
    public Class<BlockFormEvent> eventType() {
        return BlockFormEvent.class;
    }

    @Override
    public Set<String> events() {
        return Set.of("form");
    }

    @Override
    public Stream<BlockPlaceRecord> extract(BlockFormEvent event) {
        BlockSnapshot before = BlockSnapshots.capture(event.getBlock().getState());
        BlockSnapshot after = BlockSnapshots.capture(event.getNewState());
        BlockLocation location = BlockLocations.fromLocation(event.getBlock().getLocation());
        RecordContext ctx = support.environmentContext("block-form", location);
        return Stream.of(BlockPlaceRecord.of(ctx, "form", after.material().name(), before, after));
    }
}
