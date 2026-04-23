package net.medievalrp.omniscience2.plugin.listener.environment;

import java.time.Instant;
import java.util.stream.Stream;
import net.medievalrp.omniscience2.api.event.BlockBreakRecord;
import net.medievalrp.omniscience2.api.event.BlockSnapshot;
import net.medievalrp.omniscience2.api.event.Origin;
import net.medievalrp.omniscience2.api.event.Source;
import net.medievalrp.omniscience2.api.extension.EventExtractor;
import net.medievalrp.omniscience2.api.util.BlockLocation;
import net.medievalrp.omniscience2.plugin.listener.ExtractorSupport;
import net.medievalrp.omniscience2.plugin.util.BlockLocations;
import net.medievalrp.omniscience2.plugin.util.BlockSnapshots;
import org.bukkit.block.Block;
import org.bukkit.event.block.BlockExplodeEvent;

public final class BlockExplodeExtractor implements EventExtractor<BlockExplodeEvent, BlockBreakRecord> {

    private final ExtractorSupport support;

    public BlockExplodeExtractor(ExtractorSupport support) {
        this.support = support;
    }

    @Override
    public Class<BlockExplodeEvent> eventType() {
        return BlockExplodeEvent.class;
    }

    @Override
    public Stream<BlockBreakRecord> extract(BlockExplodeEvent event) {
        String cause = event.getBlock().getType().name();
        Instant occurred = support.now();
        Origin origin = support.environmentOrigin("block-explode:" + cause);
        Source source = support.environmentSource("block-explode:" + cause);
        return event.blockList().stream().map(block -> toRecord(block, occurred, origin, source));
    }

    private BlockBreakRecord toRecord(Block block, Instant occurred, Origin origin, Source source) {
        BlockSnapshot original = BlockSnapshots.capture(block.getState());
        BlockSnapshot after = BlockSnapshots.air();
        BlockLocation location = BlockLocations.fromLocation(block.getLocation());
        return new BlockBreakRecord(
                support.newId(),
                1,
                "break",
                occurred,
                support.expiresAt(occurred),
                origin,
                source,
                location,
                original.material().name(),
                original,
                after);
    }
}
