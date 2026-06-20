package net.medievalrp.spyglass.plugin.listener.entity;

import java.util.Base64;
import java.util.Set;
import net.medievalrp.spyglass.api.event.EntityDeathRecord;
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
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
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
        return Set.of("death", "drop");
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

        // Entity NBT is rollbackable (a death rollback respawns the mob via
        // Bukkit.getUnsafe().deserializeEntity), so it must be serialized here,
        // at event time, on the main thread: NMS entity reads are not
        // thread-safe and the victim is removed once the event returns, so this
        // cannot be deferred off-thread the way item blobs can (#129).
        String nbt = serializeEntity(victim);

        RecordContext ctx = support.context(origin, source, location);
        recorder.record(EntityDeathRecord.of(ctx, entityType, entityType, victim.getUniqueId(),
                killerType, damageCause, nbt));

        // Per-item drop records so loot tables are searchable like v1. Source is
        // the dead entity (CREEPER, ARMADILLO, ...); origin is whoever triggered
        // the death (player, or environment/mob). Skip for players -- their
        // drops are attributed to PlayerDropItemEvent instead when they toss
        // items, and death-drops of player inventory are noisy.
        if (victim instanceof Player) {
            return;
        }
        Source dropSource = support.entitySource(victim.getUniqueId(), entityType);
        for (ItemStack drop : event.getDrops()) {
            // Projection (no base64 blob): ItemDropRecord is forensic-only and
            // never rolled back or salvaged, so the blob is dead weight and the
            // serializeAsBytes() it skips is the dominant per-drop main-thread
            // cost on a mob farm (#129, matching ItemDropListener / #103).
            StoredItem stored = ItemSerialization.storedItemProjection(0, drop);
            if (stored == null) {
                continue;
            }
            RecordContext dropCtx = support.context(origin, dropSource, location);
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
