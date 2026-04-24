package net.medievalrp.omniscience2.plugin.listener.item;

import java.util.stream.Stream;
import net.medievalrp.omniscience2.api.event.ItemDropRecord;
import net.medievalrp.omniscience2.api.event.RecordContext;
import net.medievalrp.omniscience2.api.event.StoredItem;
import net.medievalrp.omniscience2.api.extension.EventExtractor;
import net.medievalrp.omniscience2.api.util.BlockLocation;
import net.medievalrp.omniscience2.plugin.listener.ExtractorSupport;
import net.medievalrp.omniscience2.plugin.util.BlockLocations;
import net.medievalrp.omniscience2.plugin.util.ItemSerialization;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.ItemStack;

public final class ItemDropExtractor implements EventExtractor<PlayerDropItemEvent, ItemDropRecord> {

    private final ExtractorSupport support;

    public ItemDropExtractor(ExtractorSupport support) {
        this.support = support;
    }

    @Override
    public Class<PlayerDropItemEvent> eventType() {
        return PlayerDropItemEvent.class;
    }

    @Override
    public Stream<ItemDropRecord> extract(PlayerDropItemEvent event) {
        ItemStack stack = event.getItemDrop().getItemStack();
        StoredItem stored = ItemSerialization.storedItem(0, stack);
        if (stored == null) {
            return Stream.empty();
        }
        BlockLocation location = BlockLocations.fromLocation(event.getPlayer().getLocation());
        RecordContext ctx = support.playerContext(event.getPlayer(), location);
        return Stream.of(ItemDropRecord.of(ctx, stack.getType().name(), stack.getAmount(), stored));
    }
}
