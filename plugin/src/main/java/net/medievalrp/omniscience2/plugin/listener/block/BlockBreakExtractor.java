package net.medievalrp.omniscience2.plugin.listener.block;

import java.time.Instant;
import java.util.stream.Stream;
import net.medievalrp.omniscience2.api.event.BlockBreakRecord;
import net.medievalrp.omniscience2.api.event.BlockSnapshot;
import net.medievalrp.omniscience2.api.extension.EventExtractor;
import net.medievalrp.omniscience2.api.util.BlockLocation;
import net.medievalrp.omniscience2.plugin.listener.ExtractorSupport;
import net.medievalrp.omniscience2.plugin.util.BlockLocations;
import net.medievalrp.omniscience2.plugin.util.BlockSnapshots;
import org.bukkit.event.block.BlockBreakEvent;

public final class BlockBreakExtractor implements EventExtractor<BlockBreakEvent, BlockBreakRecord> {

    private final ExtractorSupport support;

    public BlockBreakExtractor(ExtractorSupport support) {
        this.support = support;
    }

    @Override
    public Class<BlockBreakEvent> eventType() {
        return BlockBreakEvent.class;
    }

    @Override
    public Stream<BlockBreakRecord> extract(BlockBreakEvent event) {
        BlockSnapshot original = BlockSnapshots.capture(event.getBlock().getState());
        BlockSnapshot after = BlockSnapshots.air();
        BlockLocation location = BlockLocations.fromLocation(event.getBlock().getLocation());
        Instant occurred = support.now();
        return Stream.of(new BlockBreakRecord(
                support.newId(),
                1,
                "break",
                occurred,
                support.expiresAt(occurred),
                support.playerOrigin(),
                support.playerSource(event.getPlayer()),
                location,
                original.material().name(),
                original,
                after));
    }
}
