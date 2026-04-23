package net.medievalrp.spyglass.plugin.listener.item;

import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;
import net.medievalrp.spyglass.api.event.ItemPickupRecord;
import net.medievalrp.spyglass.api.event.Origin;
import net.medievalrp.spyglass.api.event.Source;
import net.medievalrp.spyglass.api.event.StoredItem;
import net.medievalrp.spyglass.api.extension.EventExtractor;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.plugin.listener.ExtractorSupport;
import net.medievalrp.spyglass.plugin.util.BlockLocations;
import net.medievalrp.spyglass.plugin.util.ItemSerialization;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.inventory.ItemStack;

public final class ItemPickupExtractor implements EventExtractor<EntityPickupItemEvent, ItemPickupRecord> {

    private final ExtractorSupport support;

    public ItemPickupExtractor(ExtractorSupport support) {
        this.support = support;
    }

    @Override
    public Class<EntityPickupItemEvent> eventType() {
        return EntityPickupItemEvent.class;
    }

    @Override
    public Stream<ItemPickupRecord> extract(EntityPickupItemEvent event) {
        ItemStack stack = event.getItem().getItemStack();
        StoredItem stored = ItemSerialization.storedItem(0, stack);
        if (stored == null) {
            return Stream.empty();
        }
        BlockLocation location = BlockLocations.fromLocation(event.getItem().getLocation());
        Instant occurred = support.now();
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
        return Stream.of(new ItemPickupRecord(
                support.newId(),
                1,
                "pickup",
                occurred,
                support.expiresAt(occurred),
                origin,
                source,
                location,
                stack.getType().name(),
                stack.getAmount(),
                stored));
    }
}
