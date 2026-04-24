package net.medievalrp.spyglass.plugin.listener.entity;

import java.util.Base64;
import java.util.Set;
import net.medievalrp.spyglass.api.event.EntityDeathRecord;
import net.medievalrp.spyglass.api.event.Origin;
import net.medievalrp.spyglass.api.event.RecordContext;
import net.medievalrp.spyglass.api.event.Source;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.plugin.listener.RecordingSupport;
import net.medievalrp.spyglass.plugin.listener.RecordingListener;
import net.medievalrp.spyglass.plugin.pipeline.Recorder;
import net.medievalrp.spyglass.plugin.util.BlockLocations;
import org.bukkit.Bukkit;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDeathEvent;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class EntityDeathListener implements RecordingListener {

    private final Recorder recorder;
    private final RecordingSupport support;

    public EntityDeathListener(Recorder recorder, RecordingSupport support) {
        this.recorder = recorder;
        this.support = support;
    }

    @Override
    public Set<String> events() {
        return Set.of("death");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        LivingEntity victim = event.getEntity();
        BlockLocation location = BlockLocations.fromLocation(victim.getLocation());
        String entityType = victim.getType().getKey().getKey();

        Origin origin;
        Source source;
        String killerType = null;
        if (victim.getKiller() instanceof Player killer) {
            origin = support.playerOrigin();
            source = support.playerSource(killer);
            killerType = "player";
        } else if (victim.getLastDamageCause() != null) {
            String cause = victim.getLastDamageCause().getCause().name();
            origin = support.environmentOrigin("death:" + cause);
            source = support.environmentSource("death:" + cause);
            killerType = cause;
        } else {
            origin = support.environmentOrigin("death");
            source = support.environmentSource("death");
        }

        String damageCause = victim.getLastDamageCause() != null
                ? victim.getLastDamageCause().getCause().name()
                : "UNKNOWN";

        String nbt = serializeEntity(victim);

        RecordContext ctx = support.context(origin, source, location);
        recorder.record(EntityDeathRecord.of(ctx, entityType, entityType, victim.getUniqueId(),
                killerType, damageCause, nbt));
    }

    private static String serializeEntity(LivingEntity entity) {
        try {
            byte[] bytes = Bukkit.getUnsafe().serializeEntity(entity);
            return Base64.getEncoder().encodeToString(bytes);
        } catch (Throwable thrown) {
            return null;
        }
    }
}
