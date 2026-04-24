package net.medievalrp.spyglass.plugin.listener.item;

import java.util.Set;
import java.util.stream.Stream;
import net.medievalrp.spyglass.api.event.ItemDropRecord;
import net.medievalrp.spyglass.api.event.RecordContext;
import net.medievalrp.spyglass.api.event.StoredItem;
import net.medievalrp.spyglass.api.extension.EventExtractor;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.plugin.listener.ExtractorSupport;
import net.medievalrp.spyglass.plugin.util.BlockLocations;
import net.medievalrp.spyglass.plugin.util.ItemSerialization;
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
    public Set<String> events() {
        return Set.of("drop");
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
