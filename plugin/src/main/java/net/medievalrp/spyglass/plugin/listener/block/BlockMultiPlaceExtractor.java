package net.medievalrp.spyglass.plugin.listener.block;

import java.time.Instant;
import java.util.stream.Stream;
import net.medievalrp.spyglass.api.event.BlockPlaceRecord;
import net.medievalrp.spyglass.api.event.BlockSnapshot;
import net.medievalrp.spyglass.api.event.Origin;
import net.medievalrp.spyglass.api.event.Source;
import net.medievalrp.spyglass.api.extension.EventExtractor;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.plugin.listener.ExtractorSupport;
import net.medievalrp.spyglass.plugin.util.BlockLocations;
import net.medievalrp.spyglass.plugin.util.BlockSnapshots;
import org.bukkit.block.BlockState;
import org.bukkit.event.block.BlockMultiPlaceEvent;

public final class BlockMultiPlaceExtractor implements EventExtractor<BlockMultiPlaceEvent, BlockPlaceRecord> {

    private final ExtractorSupport support;

    public BlockMultiPlaceExtractor(ExtractorSupport support) {
        this.support = support;
    }

    @Override
    public Class<BlockMultiPlaceEvent> eventType() {
        return BlockMultiPlaceEvent.class;
    }

    @Override
    public Stream<BlockPlaceRecord> extract(BlockMultiPlaceEvent event) {
        Instant occurred = support.now();
        Origin origin = support.playerOrigin();
        Source source = support.playerSource(event.getPlayer());
        return event.getReplacedBlockStates().stream().map(state -> toRecord(state, occurred, origin, source));
    }

    private BlockPlaceRecord toRecord(BlockState replaced, Instant occurred, Origin origin, Source source) {
        BlockSnapshot before = BlockSnapshots.capture(replaced);
        BlockSnapshot after = BlockSnapshots.capture(replaced.getBlock().getState());
        BlockLocation location = BlockLocations.fromLocation(replaced.getLocation());
        return new BlockPlaceRecord(
                support.newId(),
                1,
                "place",
                occurred,
                support.expiresAt(occurred),
                origin,
                source,
                location,
                after.material().name(),
                before,
                after);
    }
}
