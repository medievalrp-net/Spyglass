package net.medievalrp.omniscience2.plugin.listener.modern;

import java.time.Instant;
import java.util.stream.Stream;
import net.medievalrp.omniscience2.api.event.ContainerWithdrawRecord;
import net.medievalrp.omniscience2.api.event.EventRecord;
import net.medievalrp.omniscience2.api.event.StoredItem;
import net.medievalrp.omniscience2.api.extension.EventExtractor;
import net.medievalrp.omniscience2.api.util.BlockLocation;
import net.medievalrp.omniscience2.plugin.listener.ExtractorSupport;
import net.medievalrp.omniscience2.plugin.util.BlockLocations;
import net.medievalrp.omniscience2.plugin.util.ItemSerialization;
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
