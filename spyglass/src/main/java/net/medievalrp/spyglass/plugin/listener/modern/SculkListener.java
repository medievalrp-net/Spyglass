package net.medievalrp.spyglass.plugin.listener.modern;

import java.util.Set;
import net.medievalrp.spyglass.api.event.BlockUseRecord;
import net.medievalrp.spyglass.api.event.RecordContext;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.plugin.listener.RecordingSupport;
import net.medievalrp.spyglass.plugin.listener.RecordingListener;
import net.medievalrp.spyglass.plugin.pipeline.Recorder;
import net.medievalrp.spyglass.plugin.util.BlockLocations;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockReceiveGameEvent;
import org.jetbrains.annotations.ApiStatus;

/**
 * Logs when a player triggers a sculk sensor or shrieker by vibration —
 * footsteps, interaction, chest-opening nearby, etc. Non-player triggers
 * (item drops, redstone, mob movement) are skipped: the operator-useful
 * signal is "who was sneaking around this base," not warning-activation
 * noise.
 *
 * <p>v1 also hooked {@code BlockReceiveGameEvent}; wave-7's initial port
 * hooked {@code SculkBloomEvent} by mistake, which only fires when a
 * sculk catalyst grows around a death.
 */
@ApiStatus.Internal
public final class SculkListener implements RecordingListener {

    private final Recorder recorder;
    private final RecordingSupport support;

    public SculkListener(Recorder recorder, RecordingSupport support) {
        this.recorder = recorder;
        this.support = support;
    }

    @Override
    public Set<String> events() {
        return Set.of("sculk");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockReceiveGame(BlockReceiveGameEvent event) {
        Block block = event.getBlock();
        if (!isSculkTrigger(block.getType())) {
            return;
        }
        Entity entity = event.getEntity();
        if (!(entity instanceof Player player)) {
            return;
        }
        BlockLocation location = BlockLocations.fromLocation(block.getLocation());
        RecordContext ctx = support.playerContext(player, location);
        recorder.record(new BlockUseRecord(
                ctx.id(), ctx.schemaVersion(), "sculk",
                ctx.occurred(), ctx.expiresAt(),
                ctx.origin(), ctx.source(), ctx.location(),
                block.getType().name()));
    }

    private static boolean isSculkTrigger(Material type) {
        return type == Material.SCULK_SENSOR
                || type == Material.CALIBRATED_SCULK_SENSOR
                || type == Material.SCULK_SHRIEKER;
    }
}
