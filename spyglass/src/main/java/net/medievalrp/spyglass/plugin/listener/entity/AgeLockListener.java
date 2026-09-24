package net.medievalrp.spyglass.plugin.listener.entity;

import io.papermc.paper.event.player.PlayerToggleEntityAgeLockEvent;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import net.medievalrp.spyglass.api.event.CustomRecord;
import net.medievalrp.spyglass.plugin.listener.RecordingListener;
import net.medievalrp.spyglass.plugin.listener.RecordingSupport;
import net.medievalrp.spyglass.plugin.pipeline.Recorder;
import net.medievalrp.spyglass.plugin.util.BlockLocations;
import org.bukkit.entity.Ageable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;

/** Golden-dandelion audit. Intentionally not reversible: aging may have progressed since the interaction. */
public final class AgeLockListener implements RecordingListener {
    private final Recorder recorder;
    private final RecordingSupport support;
    private final Executor nextTick;

    public AgeLockListener(Recorder recorder, RecordingSupport support, Executor nextTick) {
        this.recorder = recorder;
        this.support = support;
        this.nextTick = nextTick;
    }

    @Override public Set<String> events() { return Set.of("age-lock"); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onToggle(PlayerToggleEntityAgeLockEvent event) {
        if (event.isCancelled() || !(event.getEntity() instanceof Ageable entity)) return;
        boolean before = entity.getAgeLock();
        boolean after = event.isAgeLocked();
        if (before == after) return;
        var context = support.playerContext(event.getPlayer(), BlockLocations.fromLocation(entity.getLocation()));
        String type = entity.getType().getKey().getKey();
        String id = entity.getUniqueId().toString();
        nextTick.execute(() -> {
            if (event.isCancelled() || !entity.isValid() || entity.getAgeLock() != after) return;
            recorder.record(CustomRecord.of(context, "age-lock", type,
                    before + " -> " + after,
                    Map.of("entity-id", id, "entity-type", type,
                            "before", Boolean.toString(before), "after", Boolean.toString(after))));
        });
    }
}
