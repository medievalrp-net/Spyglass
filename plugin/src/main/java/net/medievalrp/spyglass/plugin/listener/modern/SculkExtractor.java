package net.medievalrp.spyglass.plugin.listener.modern;

import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import net.medievalrp.spyglass.api.event.BlockPlaceRecord;
import net.medievalrp.spyglass.api.event.BlockSnapshot;
import net.medievalrp.spyglass.api.event.EventRecord;
import net.medievalrp.spyglass.api.extension.EventExtractor;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.plugin.listener.ExtractorSupport;
import net.medievalrp.spyglass.plugin.util.BlockLocations;
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
        Instant occurred = support.now();
        BlockSnapshot snap = new BlockSnapshot(
                block.getType(), block.getBlockData().getAsString(),
                List.of(), List.of(), List.of(), List.of(), null);
        return Stream.of(new BlockPlaceRecord(
                support.newId(), 1, "sculk", occurred,
                support.expiresAt(occurred),
                support.environmentOrigin("sculk-bloom"),
                support.environmentSource("sculk:charge=" + event.getCharge()),
                location, block.getType().name(), snap, snap));
    }
}
