package net.medievalrp.spyglass.plugin.listener.modern;

import java.util.stream.Stream;
import net.medievalrp.spyglass.api.event.BlockPlaceRecord;
import net.medievalrp.spyglass.api.event.BlockSnapshot;
import net.medievalrp.spyglass.api.event.EventRecord;
import net.medievalrp.spyglass.api.event.RecordContext;
import net.medievalrp.spyglass.api.extension.EventExtractor;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.plugin.listener.ExtractorSupport;
import net.medievalrp.spyglass.plugin.util.BlockLocations;
import net.medievalrp.spyglass.plugin.util.BlockSnapshots;
import org.bukkit.block.Block;
import org.bukkit.event.block.SculkBloomEvent;

public final class SculkExtractor implements EventExtractor<SculkBloomEvent, EventRecord> {

    private final ExtractorSupport support;

    public SculkExtractor(ExtractorSupport support) {
        this.support = support;
    }

    @Override
    public Class<SculkBloomEvent> eventType() {
        return SculkBloomEvent.class;
    }

    @Override
    public Stream<EventRecord> extract(SculkBloomEvent event) {
        Block block = event.getBlock();
        BlockLocation location = BlockLocations.fromLocation(block.getLocation());
        BlockSnapshot snap = BlockSnapshots.of(block.getType(), block.getBlockData().getAsString());
        RecordContext ctx = support.context(
                support.environmentOrigin("sculk-bloom"),
                support.environmentSource("sculk:charge=" + event.getCharge()),
                location);
        return Stream.of(BlockPlaceRecord.of(ctx, "sculk", block.getType().name(), snap, snap));
    }
}
