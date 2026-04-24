package net.medievalrp.spyglass.plugin.listener.block;

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
import org.bukkit.event.block.BlockPlaceEvent;

public final class BlockPlaceExtractor implements EventExtractor<BlockPlaceEvent, BlockPlaceRecord> {

    private final ExtractorSupport support;

    public BlockPlaceExtractor(ExtractorSupport support) {
        this.support = support;
    }

    @Override
    public Class<BlockPlaceEvent> eventType() {
        return BlockPlaceEvent.class;
    }

    @Override
    public Set<String> events() {
        return Set.of("place");
    }

    @Override
    public Stream<BlockPlaceRecord> extract(BlockPlaceEvent event) {
        BlockSnapshot before = BlockSnapshots.capture(event.getBlockReplacedState());
        BlockSnapshot after = BlockSnapshots.capture(event.getBlock().getState());
        BlockLocation location = BlockLocations.fromLocation(event.getBlock().getLocation());
        RecordContext ctx = support.playerContext(event.getPlayer(), location);
        return Stream.of(BlockPlaceRecord.of(ctx, "place", after.material().name(), before, after));
    }
}
