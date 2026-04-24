package net.medievalrp.omniscience2.plugin.listener.environment;

import java.util.Set;
import net.medievalrp.omniscience2.api.event.BlockPlaceRecord;
import net.medievalrp.omniscience2.api.event.BlockSnapshot;
import net.medievalrp.omniscience2.api.event.Origin;
import net.medievalrp.omniscience2.api.event.RecordContext;
import net.medievalrp.omniscience2.api.event.Source;
import net.medievalrp.omniscience2.api.util.BlockLocation;
import net.medievalrp.omniscience2.plugin.listener.RecordingSupport;
import net.medievalrp.omniscience2.plugin.listener.RecordingListener;
import net.medievalrp.omniscience2.plugin.pipeline.Recorder;
import net.medievalrp.omniscience2.plugin.util.BlockLocations;
import net.medievalrp.omniscience2.plugin.util.BlockSnapshots;
import org.bukkit.Material;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockIgniteEvent;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class BlockIgniteListener implements RecordingListener {

    private final Recorder recorder;
    private final RecordingSupport support;

    public BlockIgniteListener(Recorder recorder, RecordingSupport support) {
        this.recorder = recorder;
        this.support = support;
    }

    @Override
    public Set<String> events() {
        return Set.of("ignite");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockIgnite(BlockIgniteEvent event) {
        BlockState stateBefore = event.getBlock().getState();
        BlockSnapshot before = BlockSnapshots.capture(stateBefore);
        BlockSnapshot after = BlockSnapshots.of(Material.FIRE, Material.FIRE.createBlockData().getAsString());
        BlockLocation location = BlockLocations.fromLocation(event.getBlock().getLocation());

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

        RecordContext ctx = support.context(origin, source, location);
        recorder.record(BlockPlaceRecord.of(ctx, "ignite", "FIRE", before, after));
    }
}
