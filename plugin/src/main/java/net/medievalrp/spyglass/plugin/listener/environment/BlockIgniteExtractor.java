package net.medievalrp.spyglass.plugin.listener.environment;

import java.time.Instant;
import java.util.stream.Stream;
import net.medievalrp.spyglass.api.event.BlockPlaceRecord;
import net.medievalrp.spyglass.api.event.BlockSnapshot;
import net.medievalrp.spyglass.api.event.Origin;
import net.medievalrp.spyglass.api.event.Source;
import net.medievalrp.spyglass.api.extension.EventExtractor;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.plugin.listener.ExtractorSupport;
import net.medievalrp.spyglass.plugin.util.BlockLocations;
import net.medievalrp.spyglass.plugin.util.BlockSnapshots;
import org.bukkit.Material;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockIgniteEvent;

public final class BlockIgniteExtractor implements EventExtractor<BlockIgniteEvent, BlockPlaceRecord> {

    private final ExtractorSupport support;

    public BlockIgniteExtractor(ExtractorSupport support) {
        this.support = support;
    }

    @Override
    public Class<BlockIgniteEvent> eventType() {
        return BlockIgniteEvent.class;
    }

    @Override
    public Stream<BlockPlaceRecord> extract(BlockIgniteEvent event) {
        BlockState stateBefore = event.getBlock().getState();
        BlockSnapshot before = BlockSnapshots.capture(stateBefore);
        BlockSnapshot after = new BlockSnapshot(
                Material.FIRE,
                Material.FIRE.createBlockData().getAsString(),
                java.util.List.of(), java.util.List.of(), java.util.List.of(),
                java.util.List.of(), null);
        BlockLocation location = BlockLocations.fromLocation(event.getBlock().getLocation());
        Instant occurred = support.now();

        Origin origin;
        Source source;
        if (event.getPlayer() instanceof Player player) {
            origin = support.playerOrigin();
            source = support.playerSource(player);
        } else if (event.getIgnitingEntity() != null) {
            origin = support.environmentOrigin("ignite:" + event.getCause().name());
            source = support.entitySource(event.getIgnitingEntity().getUniqueId(),
                    event.getIgnitingEntity().getType().getKey().getKey());
        } else {
            origin = support.environmentOrigin("ignite:" + event.getCause().name());
            source = support.environmentSource("ignite:" + event.getCause().name());
        }

        return Stream.of(new BlockPlaceRecord(
                support.newId(),
                1,
                "ignite",
                occurred,
                support.expiresAt(occurred),
                origin,
                source,
                location,
                "FIRE",
                before,
                after));
    }
}
