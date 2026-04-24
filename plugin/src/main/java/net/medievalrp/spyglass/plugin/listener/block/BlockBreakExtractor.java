package net.medievalrp.spyglass.plugin.listener.block;

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
    public Set<String> events() {
        return Set.of("break");
    }

    @Override
    public Stream<BlockBreakRecord> extract(BlockBreakEvent event) {
        BlockSnapshot original = BlockSnapshots.capture(event.getBlock().getState());
        BlockSnapshot after = BlockSnapshots.air();
        BlockLocation location = BlockLocations.fromLocation(event.getBlock().getLocation());
        RecordContext ctx = support.playerContext(event.getPlayer(), location);
        return Stream.of(BlockBreakRecord.of(ctx, "break", original.material().name(), original, after));
    }
}
