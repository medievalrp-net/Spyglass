package net.medievalrp.omniscience2.plugin.listener.block;

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
