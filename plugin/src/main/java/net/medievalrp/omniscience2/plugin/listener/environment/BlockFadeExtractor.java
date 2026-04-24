package net.medievalrp.omniscience2.plugin.listener.environment;

import java.util.stream.Stream;
import net.medievalrp.omniscience2.api.event.BlockBreakRecord;
import net.medievalrp.omniscience2.api.event.BlockSnapshot;
import net.medievalrp.omniscience2.api.event.RecordContext;
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
        RecordContext ctx = support.environmentContext("block-fade", location);
        return Stream.of(BlockBreakRecord.of(ctx, "decay", original.material().name(), original, after));
    }
}
