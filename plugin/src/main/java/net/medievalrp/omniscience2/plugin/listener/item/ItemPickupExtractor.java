package net.medievalrp.omniscience2.plugin.listener.item;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import net.medievalrp.omniscience2.api.event.ItemPickupRecord;
import net.medievalrp.omniscience2.api.event.Origin;
import net.medievalrp.omniscience2.api.event.RecordContext;
import net.medievalrp.omniscience2.api.event.Source;
import net.medievalrp.omniscience2.api.event.StoredItem;
import net.medievalrp.omniscience2.api.extension.EventExtractor;
import net.medievalrp.omniscience2.api.util.BlockLocation;
import net.medievalrp.omniscience2.plugin.listener.ExtractorSupport;
import net.medievalrp.omniscience2.plugin.util.BlockLocations;
import net.medievalrp.omniscience2.plugin.util.ItemSerialization;
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
    public Set<String> events() {
        return Set.of("pickup");
    }

    @Override
    public Stream<ItemPickupRecord> extract(EntityPickupItemEvent event) {
        ItemStack stack = event.getItem().getItemStack();
        StoredItem stored = ItemSerialization.storedItem(0, stack);
        if (stored == null) {
            return Stream.empty();
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
        return Stream.of(ItemPickupRecord.of(ctx, stack.getType().name(), stack.getAmount(), stored));
    }
}
