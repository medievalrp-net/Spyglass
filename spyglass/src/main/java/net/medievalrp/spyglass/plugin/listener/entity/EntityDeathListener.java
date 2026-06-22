package net.medievalrp.spyglass.plugin.listener.entity;

import java.util.Base64;
import java.util.Set;
import net.medievalrp.spyglass.api.event.EntityDeathRecord;
import net.medievalrp.spyglass.api.event.EntityHitRecord;
import net.medievalrp.spyglass.api.event.ItemDropRecord;
import net.medievalrp.spyglass.api.event.Origin;
import net.medievalrp.spyglass.api.event.RecordContext;
import net.medievalrp.spyglass.api.event.Source;
import net.medievalrp.spyglass.api.event.StoredItem;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.plugin.listener.RecordingSupport;
import net.medievalrp.spyglass.plugin.listener.RecordingListener;
import net.medievalrp.spyglass.plugin.pipeline.Recorder;
import net.medievalrp.spyglass.plugin.util.BlockLocations;
import net.medievalrp.spyglass.plugin.util.ItemSerialization;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;

/**
 * Records entity deaths from two perspectives so both "who died" and "who
 * killed" are searchable by player ({@code p:} matches only {@code source}):
 *
 * <ul>
 *   <li>{@code death} — {@code source} is the <b>victim</b>, {@code target} is
 *       the killer name / mob type / damage cause. Rollbackable (respawns the
 *       victim from NBT).</li>
 *   <li>{@code kill} — {@code source} is the <b>player</b> killer, {@code target}
 *       is the victim. Stored as {@link EntityHitRecord}.</li>
 *   <li>{@code mob-kill} — {@code source} is the <b>mob</b> killer. Also
 *       {@link EntityHitRecord}, split from {@code kill} so it filters and
 *       toggles independently.</li>
 * </ul>
 */
@ApiStatus.Internal
public final class EntityDeathListener implements RecordingListener {

    private final Recorder recorder;
    private final RecordingSupport support;
    // Per-event gating: the plugin only gates at listener-registration
    // granularity, so the independent death / kill / mob-kill toggles must be
    // honoured here. Live, thread-safe view of the enabled set.
    private final Set<String> enabledEvents;

    public EntityDeathListener(Recorder recorder, RecordingSupport support, Set<String> enabledEvents) {
        this.recorder = recorder;
        this.support = support;
        this.enabledEvents = enabledEvents;
    }

    @Override
    public Set<String> events() {
        return Set.of("death", "drop", "kill", "mob-kill");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        LivingEntity victim = event.getEntity();
        BlockLocation location = BlockLocations.fromLocation(victim.getLocation());
        String victimType = victim.getType().getKey().getKey();
        java.util.UUID victimId = victim.getUniqueId();
        String victimDisplay = victim instanceof Player p ? p.getName() : victimType;

        String damageCause = victim.getLastDamageCause() != null
                ? victim.getLastDamageCause().getCause().name()
                : "UNKNOWN";

        // Resolve the killer perspective. Bukkit only fills getKiller() for
        // player killers; for mob killers we dig the damaging entity out of the
        // last damage event (resolving a projectile to its shooter), mirroring
        // EntityDamageListener.
        Player playerKiller = victim.getKiller();
        LivingEntity mobKiller = null;
        boolean projectile = false;
        String projectileType = null;
        if (victim.getLastDamageCause() instanceof EntityDamageByEntityEvent byEntity) {
            Entity damager = byEntity.getDamager();
            if (damager instanceof Projectile proj) {
                projectile = true;
                projectileType = damager.getType().getKey().getKey();
                if (proj.getShooter() instanceof Entity shooter) {
                    damager = shooter;
                }
            }
            if (playerKiller == null) {
                if (damager instanceof Player p) {
                    playerKiller = p;          // edge case getKiller() missed
                } else if (damager instanceof LivingEntity living) {
                    mobKiller = living;
                }
            }
        }

        Origin deathOrigin;
        String killerType;
        String deathTarget;
        if (playerKiller != null) {
            deathOrigin = support.playerOrigin();
            killerType = "player";
            deathTarget = playerKiller.getName();
        } else if (mobKiller != null) {
            deathOrigin = support.environmentOrigin("death:" + damageCause);
            killerType = mobKiller.getType().getKey().getKey();
            deathTarget = killerType;
        } else {
            deathOrigin = support.environmentOrigin("death:" + damageCause);
            killerType = damageCause;
            deathTarget = damageCause;
        }
        Source victimSource = victim instanceof Player p
                ? support.playerSource(p)
                : support.entitySource(victimId, victimType);

        // Entity NBT is rollbackable; serialize at event time on the main
        // thread — NMS reads aren't thread-safe and the victim is gone once the
        // event returns (#129).
        String nbt = serializeEntity(victim);

        if (enabledEvents.contains("death")) {
            RecordContext deathCtx = support.context(deathOrigin, victimSource, location);
            recorder.record(EntityDeathRecord.of(deathCtx, deathTarget, victimType, victimId,
                    killerType, damageCause, nbt));
        }

        double damage = victim.getLastDamageCause() != null
                ? victim.getLastDamageCause().getFinalDamage() : 0.0;
        if (playerKiller != null && enabledEvents.contains("kill")) {
            RecordContext killCtx = support.context(
                    support.playerOrigin(), support.playerSource(playerKiller), location);
            recorder.record(EntityHitRecord.of(killCtx, "kill", victimDisplay,
                    victimType, victimId, damage, projectile, projectileType));
        } else if (mobKiller != null && enabledEvents.contains("mob-kill")) {
            String mobType = mobKiller.getType().getKey().getKey();
            RecordContext killCtx = support.context(
                    support.environmentOrigin("kill:" + mobType),
                    support.entitySource(mobKiller.getUniqueId(), mobType), location);
            recorder.record(EntityHitRecord.of(killCtx, "mob-kill", victimDisplay,
                    victimType, victimId, damage, projectile, projectileType));
        }

        // Per-item drop records so loot tables and player death-scatter stay
        // searchable. The items belong to the victim, so attribute them to the
        // victim's source: a dead player's inventory spill is then findable as
        // `p:<name> a:drop`. (Death-scatter comes through EntityDeathEvent's
        // getDrops(), NOT PlayerDropItemEvent, so without recording it here a
        // player's death drops were captured nowhere.) Origin stays the death
        // cause so the killer/context rides along.
        if (!enabledEvents.contains("drop")) {
            return;
        }
        for (ItemStack drop : event.getDrops()) {
            StoredItem stored = ItemSerialization.storedItemProjection(0, drop);
            if (stored == null) {
                continue;
            }
            RecordContext dropCtx = support.context(deathOrigin, victimSource, location);
            recorder.record(ItemDropRecord.of(dropCtx, drop.getType().name(), drop.getAmount(), stored));
        }
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
