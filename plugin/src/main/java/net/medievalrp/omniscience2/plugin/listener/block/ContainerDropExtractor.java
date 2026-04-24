package net.medievalrp.omniscience2.plugin.listener.block;

import java.util.Set;
import java.util.stream.Stream;
import net.medievalrp.omniscience2.api.event.ItemDropRecord;
import net.medievalrp.omniscience2.api.event.RecordContext;
import net.medievalrp.omniscience2.api.event.StoredItem;
import net.medievalrp.omniscience2.api.extension.EventExtractor;
import net.medievalrp.omniscience2.api.util.BlockLocation;
import net.medievalrp.omniscience2.plugin.listener.ExtractorSupport;
import net.medievalrp.omniscience2.plugin.util.BlockLocations;
import net.medievalrp.omniscience2.plugin.util.ItemSerialization;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;

/**
 * Logs each item that falls from a broken container as its own {@code drop}
 * record. The chest's own break already carries the inventory snapshot, but
 * operators typically want to see the drops inline in search output.
 */
@ApiStatus.Internal
public final class ContainerDropExtractor implements EventExtractor<BlockDropItemEvent, ItemDropRecord> {

    private final ExtractorSupport support;

    public ContainerDropExtractor(ExtractorSupport support) {
        this.support = support;
    }

    @Override
    public Class<BlockDropItemEvent> eventType() {
        return BlockDropItemEvent.class;
    }

    @Override
    public Set<String> events() {
        return Set.of("drop");
    }

    @Override
    public Stream<ItemDropRecord> extract(BlockDropItemEvent event) {
        BlockState state = event.getBlockState();
        if (!(state instanceof Container)) {
            return Stream.empty();
        }
        Player player = event.getPlayer();
        BlockLocation location = BlockLocations.fromLocation(state.getLocation());

        Stream.Builder<ItemDropRecord> out = Stream.builder();
        int syntheticSlot = 0;
        for (Item itemEntity : event.getItems()) {
            ItemStack stack = itemEntity.getItemStack();
            if (stack == null || stack.getAmount() <= 0) {
                continue;
            }
            StoredItem stored = ItemSerialization.storedItem(syntheticSlot++, stack);
            RecordContext ctx = support.playerContext(player, location);
            out.add(ItemDropRecord.of(ctx, stack.getType().name(), stack.getAmount(), stored));
        }
        return out.build();
    }
}
