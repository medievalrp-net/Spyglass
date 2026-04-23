package net.medievalrp.spyglass.plugin.listener.environment;

import java.time.Instant;
import java.util.stream.Stream;
import net.medievalrp.spyglass.api.event.BlockBreakRecord;
import net.medievalrp.spyglass.api.event.BlockSnapshot;
import net.medievalrp.spyglass.api.event.Origin;
import net.medievalrp.spyglass.api.event.Source;
import net.medievalrp.spyglass.api.extension.EventExtractor;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.plugin.listener.ExtractorSupport;
import net.medievalrp.spyglass.plugin.util.BlockLocations;
import net.medievalrp.spyglass.plugin.util.BlockSnapshots;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.event.entity.EntityExplodeEvent;

public final class EntityExplodeExtractor implements EventExtractor<EntityExplodeEvent, BlockBreakRecord> {

    private final ExtractorSupport support;

    public EntityExplodeExtractor(ExtractorSupport support) {
        this.support = support;
    }

    @Override
    public Class<EntityExplodeEvent> eventType() {
        return EntityExplodeEvent.class;
    }

    @Override
    public Stream<BlockBreakRecord> extract(EntityExplodeEvent event) {
        Entity entity = event.getEntity();
        String entityType = entity.getType().getKey().getKey();
        Instant occurred = support.now();
        Origin origin = support.environmentOrigin("entity-explode:" + entityType);
        Source source = support.entitySource(entity.getUniqueId(), entityType);
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
