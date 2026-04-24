package net.medievalrp.spyglass.plugin.listener.environment;

import java.util.Set;
import net.medievalrp.spyglass.api.event.BlockPlaceRecord;
import net.medievalrp.spyglass.api.event.BlockSnapshot;
import net.medievalrp.spyglass.api.event.Origin;
import net.medievalrp.spyglass.api.event.RecordContext;
import net.medievalrp.spyglass.api.event.Source;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.plugin.listener.RecordingSupport;
import net.medievalrp.spyglass.plugin.listener.RecordingListener;
import net.medievalrp.spyglass.plugin.pipeline.Recorder;
import net.medievalrp.spyglass.plugin.util.BlockLocations;
import net.medievalrp.spyglass.plugin.util.BlockSnapshots;
import net.medievalrp.spyglass.plugin.util.PlayerSourceMetadata;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.ApiStatus;

/**
 * Records block ignites AND tags the resulting fire block with the
 * arsonist's UUID via {@link PlayerSourceMetadata}. When fire later
 * spreads from this block to an adjacent one, a second BlockIgniteEvent
 * fires with {@link BlockIgniteEvent.IgniteCause#SPREAD}; we log it as
 * environmental (the fire did the spread, not the player directly) but
 * inherit the metadata to the new fire block so downstream burn events
 * can still trace back to the player.
 */
@ApiStatus.Internal
public final class BlockIgniteListener implements RecordingListener {

    private final Recorder recorder;
    private final RecordingSupport support;
    private final JavaPlugin plugin;

    public BlockIgniteListener(Recorder recorder, RecordingSupport support, JavaPlugin plugin) {
        this.recorder = recorder;
        this.support = support;
        this.plugin = plugin;
    }

    @Override
    public Set<String> events() {
        return Set.of("ignite");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockIgnite(BlockIgniteEvent event) {
        Block fireBlock = event.getBlock();
        BlockState stateBefore = fireBlock.getState();
        BlockSnapshot before = BlockSnapshots.capture(stateBefore);
        BlockSnapshot after = BlockSnapshots.of(Material.FIRE, Material.FIRE.createBlockData().getAsString());
        BlockLocation location = BlockLocations.fromLocation(fireBlock.getLocation());

        Origin origin;
        Source source;
        if (event.getPlayer() instanceof Player player) {
            origin = support.playerOrigin();
            source = support.playerSource(player);
            PlayerSourceMetadata.tag(fireBlock, player, plugin);
        } else if (event.getIgnitingBlock() != null
                && !event.getIgnitingBlock().getMetadata(PlayerSourceMetadata.KEY).isEmpty()) {
            // Fire spread from a tagged block -- attribute the ignite itself
            // to environment (the fire did the spread, not the player
            // directly) but propagate the tag to the new fire block so
            // downstream breaks/burns can still find the arsonist.
            origin = support.environmentOrigin("fire-spread");
            source = support.environmentSource("fire-spread");
            PlayerSourceMetadata.inherit(event.getIgnitingBlock(), fireBlock, plugin);
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
