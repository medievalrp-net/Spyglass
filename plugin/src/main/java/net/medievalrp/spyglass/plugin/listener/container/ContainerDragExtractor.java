package net.medievalrp.spyglass.plugin.listener.container;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import net.medievalrp.spyglass.api.event.ContainerDepositRecord;
import net.medievalrp.spyglass.api.event.EventRecord;
import net.medievalrp.spyglass.api.event.StoredItem;
import net.medievalrp.spyglass.api.extension.EventExtractor;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.plugin.listener.ExtractorSupport;
import net.medievalrp.spyglass.plugin.util.BlockLocations;
import net.medievalrp.spyglass.plugin.util.ItemSerialization;
import org.bukkit.Material;
import org.bukkit.block.Container;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

public final class ContainerDragExtractor implements EventExtractor<InventoryDragEvent, EventRecord> {

    private final ExtractorSupport support;

    public ContainerDragExtractor(ExtractorSupport support) {
        this.support = support;
    }

    @Override
    public Class<InventoryDragEvent> eventType() {
        return InventoryDragEvent.class;
    }

    @Override
    public Stream<EventRecord> extract(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return Stream.empty();
        }
        Inventory top = event.getView().getTopInventory();
        InventoryHolder holder = top.getHolder();
        if (!(holder instanceof Container container)) {
            return Stream.empty();
        }
        if (holder instanceof ShulkerBox) {
            return Stream.empty();
        }
        int topSize = top.getSize();
        BlockLocation location = BlockLocations.fromLocation(container.getBlock().getLocation());
        String containerType = container.getBlock().getType().name();
        Instant occurred = support.now();
        List<EventRecord> records = new ArrayList<>();
        for (Map.Entry<Integer, ItemStack> entry : event.getNewItems().entrySet()) {
            int rawSlot = entry.getKey();
            if (rawSlot >= topSize) {
                continue;
            }
            ItemStack deposited = entry.getValue();
            if (deposited == null || deposited.getType() == Material.AIR) {
                continue;
            }
            ItemStack existing = top.getItem(rawSlot);
            int existingAmount = existing == null || existing.getType() == Material.AIR ? 0 : existing.getAmount();
            int delta = deposited.getAmount() - existingAmount;
            if (delta <= 0) {
                continue;
            }
            StoredItem storedBefore = existingAmount > 0 ? ItemSerialization.storedItem(rawSlot, existing) : null;
            StoredItem storedAfter = ItemSerialization.storedItem(rawSlot, deposited);
            records.add(new ContainerDepositRecord(
                    support.newId(), 1, "deposit", occurred,
                    support.expiresAt(occurred),
                    support.playerOrigin(), support.playerSource(player),
                    location, deposited.getType().name(), containerType,
                    rawSlot, delta, storedBefore, storedAfter));
        }
        return records.stream();
    }
}
