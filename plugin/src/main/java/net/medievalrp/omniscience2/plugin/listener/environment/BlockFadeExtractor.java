package net.medievalrp.omniscience2.plugin.listener.environment;

import java.time.Instant;
import java.util.stream.Stream;
import net.medievalrp.omniscience2.api.event.BlockBreakRecord;
import net.medievalrp.omniscience2.api.event.BlockSnapshot;
import net.medievalrp.omniscience2.api.extension.EventExtractor;
import net.medievalrp.omniscience2.api.util.BlockLocation;
import net.medievalrp.omniscience2.plugin.listener.ExtractorSupport;
import net.medievalrp.omniscience2.plugin.util.BlockLocations;
import net.medievalrp.omniscience2.plugin.util.BlockSnapshots;
import org.bukkit.event.block.BlockFadeEvent;

public final class BlockFadeExtractor implements EventExtractor<BlockFadeEvent, BlockBreakRecord> {

    private final ExtractorSupport support;

    public BlockFadeExtractor(ExtractorSupport support) {
        this.support = support;
    }

    @Override
    public Class<BlockFadeEvent> eventType() {
        return BlockFadeEvent.class;
    }

    @Override
    public Stream<BlockBreakRecord> extract(BlockFadeEvent event) {
        BlockSnapshot original = BlockSnapshots.capture(event.getBlock().getState());
        BlockSnapshot after = BlockSnapshots.capture(event.getNewState());
        BlockLocation location = BlockLocations.fromLocation(event.getBlock().getLocation());
        Instant occurred = support.now();
        return Stream.of(new BlockBreakRecord(
                support.newId(),
                1,
                "decay",
                occurred,
                support.expiresAt(occurred),
                support.environmentOrigin("block-fade"),
                support.environmentSource("block-fade"),
                location,
                original.material().name(),
                original,
                after));
    }
}
