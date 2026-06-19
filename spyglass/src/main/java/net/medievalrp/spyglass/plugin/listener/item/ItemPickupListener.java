package net.medievalrp.spyglass.plugin.listener.item;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import net.medievalrp.spyglass.api.event.ItemPickupRecord;
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
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class ItemPickupListener implements RecordingListener {

    private final Recorder recorder;
    private final RecordingSupport support;
    private final Executor serializer;

    public ItemPickupListener(Recorder recorder, RecordingSupport support, Executor serializer) {
        this.recorder = recorder;
        this.support = support;
        this.serializer = serializer;
    }

    @Override
    public Set<String> events() {
        return Set.of("pickup");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityPickupItem(EntityPickupItemEvent event) {
        ItemStack stack = event.getItem().getItemStack();
        if (stack == null || stack.getType() == Material.AIR) {
            return;
        }
        // Pickups are the highest-frequency event Spyglass logs (every
        // player plus every mob with canPickupItems). Item serialization
        // (NBT -> bytes -> base64, plus Adventure component extraction)
        // is the bulk of the per-pickup cost, so we keep only a cheap
        // snapshot on the main thread and hand the heavy work to the
        // serializer. The clone is independent of the live stack (which
        // the picked-up entity is about to consume), so serializing the
        // clone off the main thread is safe.
        ItemStack snapshot = stack.clone();
        String target = stack.getType().name();
        int amount = stack.getAmount();
        BlockLocation location = BlockLocations.fromLocation(event.getItem().getLocation());
        Origin origin;
        Source source;
        if (event.getEntity() instanceof Player player) {
            origin = support.playerOrigin();
            source = support.playerSource(player);
        } else {
            UUID entityId = event.getEntity().getUniqueId();
            String entityType = event.getEntity().getType().getKey().getKey();
            origin = support.environmentOrigin("pickup:" + entityType);
            source = support.entitySource(entityId, entityType);
        }
        // Build the context (which stamps occurred + the time-ordered v7
        // id) on the main thread, so the record reflects event time, not
        // serialization time. ItemPickupRecord is not Rollbackable, so
        // deferring it has no interaction with the rollback flush /
        // read-your-writes contract.
        RecordContext ctx = support.context(origin, source, location);
        serializer.execute(() -> {
            StoredItem stored = ItemSerialization.storedItem(0, snapshot);
            if (stored == null) {
                return;
            }
            recorder.record(ItemPickupRecord.of(ctx, target, amount, stored));
        });
    }
}
