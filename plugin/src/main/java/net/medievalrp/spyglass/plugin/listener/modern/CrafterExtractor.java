package net.medievalrp.spyglass.plugin.listener.modern;

import java.time.Instant;
import java.util.stream.Stream;
import net.medievalrp.spyglass.api.event.ContainerWithdrawRecord;
import net.medievalrp.spyglass.api.event.EventRecord;
import net.medievalrp.spyglass.api.event.StoredItem;
import net.medievalrp.spyglass.api.extension.EventExtractor;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.plugin.listener.ExtractorSupport;
import net.medievalrp.spyglass.plugin.util.BlockLocations;
import net.medievalrp.spyglass.plugin.util.ItemSerialization;
import org.bukkit.event.block.CrafterCraftEvent;
import org.bukkit.inventory.ItemStack;

public final class CrafterExtractor implements EventExtractor<CrafterCraftEvent, EventRecord> {

    private final ExtractorSupport support;

    public CrafterExtractor(ExtractorSupport support) {
        this.support = support;
    }

    @Override
    public Class<CrafterCraftEvent> eventType() {
        return CrafterCraftEvent.class;
    }

    @Override
    public Stream<EventRecord> extract(CrafterCraftEvent event) {
        ItemStack result = event.getResult();
        if (result == null) {
            return Stream.empty();
        }
        BlockLocation location = BlockLocations.fromLocation(event.getBlock().getLocation());
        Instant occurred = support.now();
        StoredItem stored = ItemSerialization.storedItem(0, result);
        return Stream.of(new ContainerWithdrawRecord(
                support.newId(), 1, "crafter", occurred,
                support.expiresAt(occurred),
                support.environmentOrigin("crafter"),
                support.environmentSource("crafter"),
                location, result.getType().name(), "CRAFTER",
                0, result.getAmount(), stored, null));
    }
}
