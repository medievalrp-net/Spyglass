package net.medievalrp.spyglass.plugin.listener.entity;

import io.papermc.paper.event.entity.EntityEquipmentChangedEvent;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import net.medievalrp.spyglass.api.event.CustomRecord;
import net.medievalrp.spyglass.api.event.RecordContext;
import net.medievalrp.spyglass.api.event.Source;
import net.medievalrp.spyglass.plugin.listener.RecordingListener;
import net.medievalrp.spyglass.plugin.listener.RecordingSupport;
import net.medievalrp.spyglass.plugin.pipeline.Recorder;
import net.medievalrp.spyglass.plugin.util.BlockLocations;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerBucketEntityEvent;
import org.bukkit.event.player.PlayerShearEntityEvent;
import org.bukkit.event.block.BlockShearEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/** Sulfur-cube changes are audit-only: reversing them could duplicate an ejected item or filled bucket. */
public final class SulfurCubeListener implements RecordingListener {
    private final Recorder recorder;
    private final RecordingSupport support;
    private final Executor nextTick;
    private final Map<UUID, RecordContext> actors = new HashMap<>();
    private final Map<UUID, String> lastContent = new HashMap<>();
    private final Map<net.medievalrp.spyglass.api.util.BlockLocation, Source> bucketActors = new HashMap<>();
    private final Set<UUID> observing = new java.util.HashSet<>();

    public SulfurCubeListener(Recorder recorder, RecordingSupport support, Executor nextTick) {
        this.recorder = recorder;
        this.support = support;
        this.nextTick = nextTick;
    }
    @Override public Set<String> events() { return Set.of("sulfur-content", "sulfur-growth", "sulfur-bucket", "sulfur-ignite"); }
    @Override public void register(org.bukkit.plugin.Plugin plugin) {
        RecordingListener.super.register(plugin);
        net.medievalrp.spyglass.plugin.listener.OptionalPaperEvents.register(plugin, this,
                "io.papermc.paper.event.entity.SulfurCubeSwallowItemEvent", event -> {
                    Entity entity = (Entity) net.medievalrp.spyglass.plugin.listener.OptionalPaperEvents.call(event, "getEntity");
                    var player = (org.bukkit.entity.Player) net.medievalrp.spyglass.plugin.listener.OptionalPaperEvents.call(event, "getPlayer");
                    observe(entity, player == null ? support.entitySource(entity.getUniqueId(), "sulfur_cube")
                            : support.playerSource(player), "swallow", ((org.bukkit.event.Cancellable) event)::isCancelled);
                });
        net.medievalrp.spyglass.plugin.listener.OptionalPaperEvents.register(plugin, this,
                "io.papermc.paper.event.entity.EntityIgniteEvent", event -> {
                    Entity entity = (Entity) net.medievalrp.spyglass.plugin.listener.OptionalPaperEvents.call(event, "getEntity");
                    if (!cube(entity)) return;
                    var context = actors.getOrDefault(entity.getUniqueId(), environment(entity, "ignite"));
                    String before = bytes(content((LivingEntity) entity));
                    nextTick.execute(() -> {
                        if (((org.bukkit.event.Cancellable) event).isCancelled() || !entity.isValid()) return;
                        int fuse = (Integer) net.medievalrp.spyglass.plugin.listener.OptionalPaperEvents.call(entity, "getFuseTicks");
                        if (fuse > 0) recorder.record(CustomRecord.of(context, "sulfur-ignite", "sulfur_cube", "ignited",
                                Map.of("content-item", before, "fuse-ticks", "" + fuse)));
                    });
                });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDispense(org.bukkit.event.block.BlockDispenseEvent event) {
        if (event.isCancelled()) return;
        if (!(event.getBlock().getBlockData() instanceof org.bukkit.block.data.Directional facing)) return;
        if (event.getItem().getType().name().equals("SULFUR_CUBE_BUCKET")) {
            var location = BlockLocations.fromBlock(event.getBlock().getRelative(facing.getFacing()));
            Source actor = support.environmentSource("dispenser:" + BlockLocations.fromBlock(event.getBlock()));
            bucketActors.put(location, actor);
            nextTick.execute(() -> bucketActors.remove(location, actor));
        }
        var front = event.getBlock().getRelative(facing.getFacing()).getLocation().add(.5, .5, .5);
        for (Entity entity : event.getBlock().getWorld().getNearbyEntities(front, .75, .75, .75)) {
            if (cube(entity)) observe(entity, support.environmentSource("dispenser:" + BlockLocations.fromBlock(event.getBlock())),
                    "dispenser", event::isCancelled);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEquip(org.bukkit.event.block.BlockDispenseArmorEvent event) {
        observe(event.getTargetEntity(), support.environmentSource("dispenser:" + BlockLocations.fromBlock(event.getBlock())),
                "dispenser-equip", event::isCancelled);
    }

    private static boolean cube(Entity entity) { return entity.getType().name().equals("SULFUR_CUBE"); }
    private static String bytes(ItemStack item) {
        return item == null || item.getType().isAir() ? "" : Base64.getEncoder().encodeToString(item.serializeAsBytes());
    }
    private static ItemStack content(LivingEntity entity) { return entity.getEquipment().getItem(EquipmentSlot.BODY); }
    private static int size(Entity entity) {
        try { return (Integer) entity.getClass().getMethod("getSize").invoke(entity); }
        catch (ReflectiveOperationException ex) { throw new IllegalStateException("Cannot read sulfur cube size", ex); }
    }
    private RecordContext context(Entity entity, Source source, String mechanism) {
        return support.context(support.environmentOrigin(mechanism), source, BlockLocations.fromLocation(entity.getLocation()))
                .withExtension("entity-id", entity.getUniqueId().toString()).withExtension("mechanism", mechanism);
    }
    private RecordContext environment(Entity entity, String mechanism) {
        return context(entity, support.entitySource(entity.getUniqueId(), "sulfur_cube"), mechanism);
    }
    private void contentChange(LivingEntity entity, String before, String after, RecordContext context) {
        if (before.equals(after) || after.equals(lastContent.get(entity.getUniqueId()))) return;
        lastContent.put(entity.getUniqueId(), after);
        String beforeMaterial = before.isEmpty() ? "AIR" : ItemStack.deserializeBytes(Base64.getDecoder().decode(before)).getType().name();
        String afterMaterial = after.isEmpty() ? "AIR" : ItemStack.deserializeBytes(Base64.getDecoder().decode(after)).getType().name();
        recorder.record(CustomRecord.of(context, "sulfur-content", "sulfur_cube", beforeMaterial + " -> " + afterMaterial,
                Map.of("before-item", before, "after-item", after, "before-material", beforeMaterial,
                        "after-material", afterMaterial, "encoding", "paper-item-base64")));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEquipment(EntityEquipmentChangedEvent event) {
        if (!cube(event.getEntity())) return;
        var change = event.getEquipmentChanges().get(EquipmentSlot.BODY);
        if (change == null) return;
        contentChange(event.getEntity(), bytes(change.oldItem()), bytes(change.newItem()),
                actors.getOrDefault(event.getEntity().getUniqueId(), environment(event.getEntity(), "equipment-change")));
    }

    private void observe(Entity entity, Source source, String mechanism, java.util.function.BooleanSupplier cancelled) {
        if (!cube(entity) || !(entity instanceof LivingEntity living) || cancelled.getAsBoolean()) return;
        var context = context(entity, source, mechanism);
        actors.put(entity.getUniqueId(), context);
        if (!observing.add(entity.getUniqueId())) return;
        String before = bytes(content(living));
        int oldSize = size(entity);
        int oldAge = ((org.bukkit.entity.Ageable) entity).getAge();
        nextTick.execute(() -> {
            observing.remove(entity.getUniqueId());
            RecordContext actor = actors.remove(entity.getUniqueId());
            if (cancelled.getAsBoolean() || !entity.isValid()) return;
            contentChange(living, before, bytes(content(living)), actor == null ? context : actor);
            int newSize = size(entity);
            int newAge = ((org.bukkit.entity.Ageable) entity).getAge();
            if (oldSize != newSize || (oldAge < 0 && newAge > oldAge + 1))
                recorder.record(CustomRecord.of(context, "sulfur-growth", "sulfur_cube",
                        "age " + oldAge + " -> " + newAge + ", size " + oldSize + " -> " + newSize,
                        Map.of("before-size", "" + oldSize, "after-size", "" + newSize,
                                "before-age", "" + oldAge, "after-age", "" + newAge)));
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        observe(event.getRightClicked(), support.playerSource(event.getPlayer()), "player-interaction", event::isCancelled);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onShear(PlayerShearEntityEvent event) {
        observe(event.getEntity(), support.playerSource(event.getPlayer()), "player-shear", event::isCancelled);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDispenserShear(BlockShearEntityEvent event) {
        observe(event.getEntity(), support.environmentSource("dispenser:" + BlockLocations.fromBlock(event.getBlock())),
                "dispenser-shear", event::isCancelled);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (cube(event.getEntity())) observe(event.getEntity(), support.entitySource(event.getEntity().getUniqueId(), "sulfur_cube"),
                "ground-absorption", event::isCancelled);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucket(PlayerBucketEntityEvent event) {
        Entity entity = event.getEntity();
        if (!cube(entity) || event.isCancelled()) return;
        var context = context(entity, support.playerSource(event.getPlayer()), "bucket-capture");
        String bucket = bytes(event.getEntityBucket());
        String before = bytes(content((LivingEntity) entity));
        nextTick.execute(() -> {
            if (!event.isCancelled() && !entity.isValid()) recorder.record(CustomRecord.of(context,
                    "sulfur-bucket", "sulfur_cube", "captured in bucket",
                    Map.of("before-item", before, "bucket-item", bucket, "encoding", "paper-item-base64")));
        });
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEmptyBucket(org.bukkit.event.player.PlayerBucketEmptyEvent event) {
        if (event.isCancelled() || !event.getBucket().name().equals("SULFUR_CUBE_BUCKET")) return;
        var location = BlockLocations.fromBlock(event.getBlock());
        Source actor = support.playerSource(event.getPlayer());
        bucketActors.put(location, actor);
        nextTick.execute(() -> bucketActors.remove(location, actor));
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        if (!cube(event.getEntity()) || event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.BUCKET) return;
        Source actor = bucketActors.get(BlockLocations.fromLocation(event.getLocation()));
        var context = actor == null ? environment(event.getEntity(), "bucket-release")
                : context(event.getEntity(), actor, "bucket-release");
        nextTick.execute(() -> {
            if (!event.isCancelled() && event.getEntity().isValid()) recorder.record(CustomRecord.of(context,
                    "sulfur-bucket", "sulfur_cube", "released from bucket",
                    Map.of("after-item", bytes(content(event.getEntity())), "encoding", "paper-item-base64")));
        });
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void onRemove(EntityRemoveEvent event) {
        if (!cube(event.getEntity())) return;
        actors.remove(event.getEntity().getUniqueId());
        lastContent.remove(event.getEntity().getUniqueId());
        observing.remove(event.getEntity().getUniqueId());
    }
}
