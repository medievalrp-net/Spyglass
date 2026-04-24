package net.medievalrp.spyglass.plugin.listener.player;

import java.util.Set;
import net.medievalrp.spyglass.api.event.RecordContext;
import net.medievalrp.spyglass.api.event.TeleportRecord;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.plugin.listener.RecordingSupport;
import net.medievalrp.spyglass.plugin.listener.RecordingListener;
import net.medievalrp.spyglass.plugin.pipeline.Recorder;
import net.medievalrp.spyglass.plugin.util.BlockLocations;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class TeleportListener implements RecordingListener {

    private final Recorder recorder;
    private final RecordingSupport support;

    public TeleportListener(Recorder recorder, RecordingSupport support) {
        this.recorder = recorder;
        this.support = support;
    }

    @Override
    public Set<String> events() {
        return Set.of("teleport");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        if (event.getTo() == null || event.getFrom() == null) {
            return;
        }
        PlayerTeleportEvent.TeleportCause cause = event.getCause();
        if (cause == PlayerTeleportEvent.TeleportCause.UNKNOWN) {
            return;
        }
        BlockLocation from = BlockLocations.fromLocation(event.getFrom());
        BlockLocation to = BlockLocations.fromLocation(event.getTo());
        RecordContext ctx = support.playerContext(event.getPlayer(), from);
        recorder.record(TeleportRecord.of(ctx, event.getPlayer().getName(), from, to, cause.name()));
    }
}
