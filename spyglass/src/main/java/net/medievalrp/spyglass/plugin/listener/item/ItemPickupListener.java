package net.medievalrp.spyglass.plugin.listener.item;

import java.util.Set;
import java.util.UUID;
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

    public ItemPickupListener(Recorder recorder, RecordingSupport support) {
        this.recorder = recorder;
        this.support = support;
    }

    @Override
    public Set<String> events() {
        return Set.of("pickup");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityPickupItem(EntityPickupItemEvent event) {
        ItemStack stack = event.getItem().getItemStack();
        StoredItem stored = ItemSerialization.storedItem(0, stack);
        if (stored == null) {
            return;
        }
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
        RecordContext ctx = support.context(origin, source, location);
        recorder.record(ItemPickupRecord.of(ctx, stack.getType().name(), stack.getAmount(), stored));
    }
}
