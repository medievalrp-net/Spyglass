package net.medievalrp.spyglass.plugin.listener.environment;

import java.util.Set;
import java.util.stream.Stream;
import net.medievalrp.spyglass.api.event.BlockBreakRecord;
import net.medievalrp.spyglass.api.event.BlockSnapshot;
import net.medievalrp.spyglass.api.event.RecordContext;
import net.medievalrp.spyglass.api.extension.EventExtractor;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.plugin.listener.ExtractorSupport;
import net.medievalrp.spyglass.plugin.util.BlockLocations;
import net.medievalrp.spyglass.plugin.util.BlockSnapshots;
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
    public Set<String> events() {
        return Set.of("decay");
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
