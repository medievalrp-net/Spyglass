package net.medievalrp.spyglass.plugin.listener.entity;

import java.time.Instant;
import java.util.Set;
import net.medievalrp.spyglass.api.event.EntityHitRecord;
import net.medievalrp.spyglass.api.event.Origin;
import net.medievalrp.spyglass.api.event.Source;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.plugin.listener.RecordingSupport;
import net.medievalrp.spyglass.plugin.listener.RecordingListener;
import net.medievalrp.spyglass.plugin.pipeline.Recorder;
import net.medievalrp.spyglass.plugin.util.BlockLocations;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.projectiles.ProjectileSource;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class EntityDamageListener implements RecordingListener {

    private final Recorder recorder;
    private final RecordingSupport support;

    public EntityDamageListener(Recorder recorder, RecordingSupport support) {
        this.recorder = recorder;
        this.support = support;
    }

    @Override
    public Set<String> events() {
        return Set.of("hit", "shot");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        Entity victim = event.getEntity();
        Entity damager = event.getDamager();
        Instant occurred = support.now();
        BlockLocation location = BlockLocations.fromLocation(victim.getLocation());
        String victimType = victim.getType().getKey().getKey();

        boolean projectile = damager instanceof Projectile;
        String projectileType = projectile ? damager.getType().getKey().getKey() : null;

        Origin origin;
        Source source;
        Entity effectiveShooter = damager;
        if (projectile) {
            Projectile proj = (Projectile) damager;
            ProjectileSource shooter = proj.getShooter();
            if (shooter instanceof Entity entityShooter) {
                effectiveShooter = entityShooter;
            }
        }
        if (effectiveShooter instanceof Player player) {
            origin = support.playerOrigin();
            source = support.playerSource(player);
        } else {
            origin = support.environmentOrigin("damage:" + effectiveShooter.getType().getKey().getKey());
            source = support.entitySource(effectiveShooter.getUniqueId(),
                    effectiveShooter.getType().getKey().getKey());
        }

        String eventName = projectile ? "shot" : "hit";
        recorder.record(new EntityHitRecord(
                support.newId(),
                1,
                eventName,
                occurred,
                support.expiresAt(occurred),
                origin,
                source,
                location,
                victimType,
                victimType,
                victim.getUniqueId(),
                event.getDamage(),
                projectile,
                projectileType));
    }
}
