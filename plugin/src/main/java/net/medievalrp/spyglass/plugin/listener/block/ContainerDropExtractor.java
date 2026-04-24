package net.medievalrp.spyglass.plugin.listener.block;

import java.time.Instant;
import java.util.stream.Stream;
import net.medievalrp.spyglass.api.event.ItemDropRecord;
import net.medievalrp.spyglass.api.event.Origin;
import net.medievalrp.spyglass.api.event.Source;
import net.medievalrp.spyglass.api.event.StoredItem;
import net.medievalrp.spyglass.api.extension.EventExtractor;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.plugin.listener.ExtractorSupport;
import net.medievalrp.spyglass.plugin.util.BlockLocations;
import net.medievalrp.spyglass.plugin.util.ItemSerialization;
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
    public Stream<ItemDropRecord> extract(BlockDropItemEvent event) {
        BlockState state = event.getBlockState();
        if (!(state instanceof Container)) {
            return Stream.empty();
        }
        Player player = event.getPlayer();
        Instant occurred = support.now();
        BlockLocation location = BlockLocations.fromLocation(state.getLocation());
        Origin origin = support.playerOrigin();
        Source source = support.playerSource(player);

        Stream.Builder<ItemDropRecord> out = Stream.builder();
        int syntheticSlot = 0;
        for (Item itemEntity : event.getItems()) {
            ItemStack stack = itemEntity.getItemStack();
            if (stack == null || stack.getAmount() <= 0) {
                continue;
            }
            StoredItem stored = ItemSerialization.storedItem(syntheticSlot++, stack);
            out.add(new ItemDropRecord(
                    support.newId(), 1, "drop", occurred,
                    support.expiresAt(occurred),
                    origin, source, location,
                    stack.getType().name(),
                    stack.getAmount(), stored));
        }
        return out.build();
    }
}
