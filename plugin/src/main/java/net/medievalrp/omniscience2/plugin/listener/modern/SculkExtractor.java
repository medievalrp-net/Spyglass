package net.medievalrp.omniscience2.plugin.listener.modern;

import java.time.Instant;
import java.util.stream.Stream;
import net.medievalrp.omniscience2.api.event.BlockPlaceRecord;
import net.medievalrp.omniscience2.api.event.BlockSnapshot;
import net.medievalrp.omniscience2.api.event.EventRecord;
import net.medievalrp.omniscience2.api.extension.EventExtractor;
import net.medievalrp.omniscience2.api.util.BlockLocation;
import net.medievalrp.omniscience2.plugin.listener.ExtractorSupport;
import net.medievalrp.omniscience2.plugin.util.BlockLocations;
import net.medievalrp.omniscience2.plugin.util.BlockSnapshots;
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
        BlockSnapshot snap = BlockSnapshots.of(block.getType(), block.getBlockData().getAsString());
        return Stream.of(new BlockPlaceRecord(
                support.newId(), 1, "sculk", occurred,
                support.expiresAt(occurred),
                support.environmentOrigin("sculk-bloom"),
                support.environmentSource("sculk:charge=" + event.getCharge()),
                location, block.getType().name(), snap, snap));
    }
}
