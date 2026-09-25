package net.medievalrp.spyglass.plugin.listener.entity;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import net.medievalrp.spyglass.api.event.EntityLifecycleRecord;
import net.medievalrp.spyglass.api.event.Source;
import net.medievalrp.spyglass.plugin.listener.RecordingListener;
import net.medievalrp.spyglass.plugin.listener.RecordingSupport;
import net.medievalrp.spyglass.plugin.pipeline.Recorder;
import net.medievalrp.spyglass.plugin.util.BlockLocations;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;

/** Name-gated so older target jars never link against the 26.3 Cushion API. */
public final class CushionListener implements RecordingListener {
    private final Recorder recorder;
    private final RecordingSupport support;
    private final Executor nextTick;
    private final Map<UUID, String> snapshots = new HashMap<>();
    private final Map<UUID, Source> actors = new HashMap<>();
    private final Set<UUID> pendingBreaks = new java.util.HashSet<>();
    private org.bukkit.plugin.Plugin plugin;

    public CushionListener(Recorder recorder, RecordingSupport support, Executor nextTick) {
        this.recorder = recorder;
        this.support = support;
        this.nextTick = nextTick;
    }
    @Override public Set<String> events() { return Set.of("cushion-place", "cushion-break"); }
    @Override public void register(org.bukkit.plugin.Plugin plugin) {
        this.plugin = plugin;
        RecordingListener.super.register(plugin);
        net.medievalrp.spyglass.plugin.listener.OptionalPaperEvents.register(plugin, this,
                "io.papermc.paper.event.entity.EntityBreakEvent", event -> {
                    Entity entity = (Entity) net.medievalrp.spyglass.plugin.listener.OptionalPaperEvents.call(event, "getEntity");
                    if (!cushion(entity)) return;
                    UUID id = entity.getUniqueId();
                    String cause = net.medievalrp.spyglass.plugin.listener.OptionalPaperEvents.call(event, "getCause").toString();
                    Source actor = cause.equals("PHYSICS") ? actors.get(id) : null;
                    if (event.getClass().getSimpleName().equals("EntityBreakByEntityEvent")) {
                        Entity remover = (Entity) net.medievalrp.spyglass.plugin.listener.OptionalPaperEvents.call(event, "getRemover");
                        if (remover instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter) remover = shooter;
                        if (remover != null) actor = remover instanceof Player player ? support.playerSource(player)
                                : support.entitySource(remover.getUniqueId(), remover.getType().getKey().getKey());
                    }
                    String nbt = snapshot(entity), target = description(entity);
                    var context = support.context(support.environmentOrigin("cushion-" + cause.toLowerCase(java.util.Locale.ROOT)),
                            actor == null ? support.environmentSource(cause) : actor, BlockLocations.fromLocation(entity.getLocation()));
                    if (!pendingBreaks.add(id)) return;
                    nextTick.execute(() -> {
                        pendingBreaks.remove(id);
                        if (((org.bukkit.event.Cancellable) event).isCancelled() || entity.isValid()) return;
                        snapshots.remove(id); actors.remove(id);
                        recorder.record(EntityLifecycleRecord.of(context, "cushion-break", target, "cushion", id, nbt));
                    });
                });
    }
    private static boolean cushion(Entity entity) { return entity.getType().name().equals("CUSHION"); }

    private String snapshot(Entity entity) {
        try {
            String nbt = Base64.getEncoder().encodeToString(Bukkit.getUnsafe().serializeEntity(entity));
            snapshots.put(entity.getUniqueId(), nbt);
            return nbt;
        } catch (RuntimeException ex) {
            return snapshots.get(entity.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(EntityPlaceEvent event) {
        Entity entity = event.getEntity();
        if (!cushion(entity) || event.isCancelled()) return;
        var context = event.getPlayer() == null
                ? support.environmentContext("cushion-place", BlockLocations.fromLocation(entity.getLocation()))
                : support.playerContext(event.getPlayer(), BlockLocations.fromLocation(entity.getLocation()));
        nextTick.execute(() -> {
            if (event.isCancelled() || !entity.isValid()) return;
            recorder.record(EntityLifecycleRecord.of(context, "cushion-place", description(entity),
                    "cushion", entity.getUniqueId(), snapshot(entity)));
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!cushion(event.getEntity()) || event.isCancelled()) return;
        Entity damager = event.getDamager();
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter) damager = shooter;
        rememberActor(event.getEntity(), damager instanceof Player player ? support.playerSource(player)
                : support.entitySource(damager.getUniqueId(), damager.getType().getKey().getKey()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSupportBreak(BlockBreakEvent event) {
        if (event.isCancelled()) return;
        for (Entity entity : event.getBlock().getWorld().getNearbyEntities(event.getBlock().getLocation().add(.5, 1, .5), 1, 1, 1)) {
            if (cushion(entity) && entity.getLocation().getBlock().getRelative(org.bukkit.block.BlockFace.DOWN).equals(event.getBlock()))
                rememberActor(entity, support.playerSource(event.getPlayer()));
        }
    }

    private void rememberActor(Entity entity, Source source) {
        UUID id = entity.getUniqueId();
        snapshot(entity);
        actors.put(id, source);
        if (plugin != null) plugin.getServer().getScheduler().runTaskLater(plugin, () -> actors.remove(id, source), 200L);
        else nextTick.execute(() -> actors.remove(id, source));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRemove(EntityRemoveEvent event) {
        Entity entity = event.getEntity();
        if (!cushion(entity) || pendingBreaks.contains(entity.getUniqueId())) return;
        String nbt = snapshot(entity);
        snapshots.remove(entity.getUniqueId());
        Source actor = actors.remove(entity.getUniqueId());
        // Chunk unload is not destruction; plugin removals include Spyglass rollback itself.
        if (event.getCause() == EntityRemoveEvent.Cause.UNLOAD || event.getCause() == EntityRemoveEvent.Cause.PLUGIN) return;
        String cause = "cushion-" + event.getCause().name().toLowerCase(java.util.Locale.ROOT);
        var context = support.context(support.environmentOrigin(cause),
                actor == null ? support.environmentSource(cause) : actor, BlockLocations.fromLocation(entity.getLocation()));
        recorder.record(EntityLifecycleRecord.of(context, "cushion-break", description(entity),
                "cushion", entity.getUniqueId(), nbt));
    }

    private static String description(Entity entity) {
        return entity instanceof org.bukkit.material.Colorable color ? color.getColor().name() + "_CUSHION" : "CUSHION";
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAdded(com.destroystokyo.paper.event.entity.EntityAddToWorldEvent event) {
        if (cushion(event.getEntity())) snapshot(event.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLoad(EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) if (cushion(entity)) snapshot(entity);
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void onUnload(EntitiesUnloadEvent event) {
        for (Entity entity : event.getEntities()) {
            snapshots.remove(entity.getUniqueId());
            actors.remove(entity.getUniqueId());
        }
    }
}
