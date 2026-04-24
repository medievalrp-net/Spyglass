package net.medievalrp.spyglass.plugin.listener.player;

import java.util.Set;
import net.medievalrp.spyglass.api.event.JoinRecord;
import net.medievalrp.spyglass.api.event.RecordContext;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.plugin.listener.RecordingSupport;
import net.medievalrp.spyglass.plugin.listener.RecordingListener;
import net.medievalrp.spyglass.plugin.pipeline.Recorder;
import net.medievalrp.spyglass.plugin.util.BlockLocations;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerJoinEvent;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class JoinListener implements RecordingListener {

    private final Recorder recorder;
    private final RecordingSupport support;

    public JoinListener(Recorder recorder, RecordingSupport support) {
        this.recorder = recorder;
        this.support = support;
    }

    @Override
    public Set<String> events() {
        return Set.of("join");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerJoin(PlayerJoinEvent event) {
        BlockLocation location = BlockLocations.fromLocation(event.getPlayer().getLocation());
        String address = event.getPlayer().getAddress() == null
                ? ""
                : event.getPlayer().getAddress().getAddress().getHostAddress();
        RecordContext ctx = support.playerContext(event.getPlayer(), location);
        recorder.record(JoinRecord.of(ctx, event.getPlayer().getName(), address));
    }
}
