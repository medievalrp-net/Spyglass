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
    public Stream<BlockPlaceRecord> extract(BlockFormEvent event) {
        BlockSnapshot before = BlockSnapshots.capture(event.getBlock().getState());
        BlockSnapshot after = BlockSnapshots.capture(event.getNewState());
        BlockLocation location = BlockLocations.fromLocation(event.getBlock().getLocation());
        Instant occurred = support.now();
        return Stream.of(new BlockPlaceRecord(
                support.newId(),
                1,
                "form",
                occurred,
                support.expiresAt(occurred),
                support.environmentOrigin("block-form"),
                support.environmentSource("block-form"),
                location,
                after.material().name(),
                before,
                after));
    }
}
