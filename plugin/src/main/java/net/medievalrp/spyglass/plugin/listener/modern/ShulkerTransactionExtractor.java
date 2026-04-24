package net.medievalrp.spyglass.plugin.listener.modern;

import java.time.Instant;
import java.util.Set;
import java.util.stream.Stream;
import net.medievalrp.spyglass.api.event.ContainerDepositRecord;
import net.medievalrp.spyglass.api.event.ContainerWithdrawRecord;
import net.medievalrp.spyglass.api.event.EventRecord;
import net.medievalrp.spyglass.api.event.StoredItem;
import net.medievalrp.spyglass.api.extension.EventExtractor;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.plugin.listener.ExtractorSupport;
import net.medievalrp.spyglass.plugin.util.BlockLocations;
import net.medievalrp.spyglass.plugin.util.InventoryActions;
import net.medievalrp.spyglass.plugin.util.InventoryActions.Direction;
import net.medievalrp.spyglass.plugin.util.ItemSerialization;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

public final class ShulkerTransactionExtractor implements EventExtractor<InventoryClickEvent, EventRecord> {

    private final ExtractorSupport support;

    public ShulkerTransactionExtractor(ExtractorSupport support) {
        this.support = support;
    }

    @Override
    public Class<InventoryClickEvent> eventType() {
        return InventoryClickEvent.class;
    }

    @Override
    public Set<String> events() {
        return Set.of("shulker-deposit", "shulker-withdraw");
    }

    @Override
    public Stream<EventRecord> extract(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return Stream.empty();
        }
        Inventory clicked = event.getClickedInventory();
        if (clicked == null) {
            return Stream.empty();
        }
        InventoryHolder holder = clicked.getHolder();
        if (!(holder instanceof ShulkerBox shulker)) {
            return Stream.empty();
        }

        InventoryAction action = event.getAction();
        Direction direction = InventoryActions.directionOf(action);
        if (direction == null) {
            return Stream.empty();
        }
        int slot = event.getSlot();
        ItemStack slotItem = clicked.getItem(slot);
        ItemStack cursor = event.getCursor();
        int amount = InventoryActions.amountOf(action, slotItem, cursor);
        if (amount <= 0) {
            return Stream.empty();
        }

        BlockLocation location = BlockLocations.fromLocation(shulker.getBlock().getLocation());
        String containerType = shulker.getBlock().getType().name();
        StoredItem before = ItemSerialization.storedItem(slot, slotItem);
        Instant occurred = support.now();

        EventRecord record = switch (direction) {
            case DEPOSIT -> new ContainerDepositRecord(
                    support.newId(), 1, "shulker-deposit", occurred,
                    support.expiresAt(occurred),
                    support.playerOrigin(), support.playerSource(player),
                    location, cursor == null ? "UNKNOWN" : cursor.getType().name(),
                    containerType, slot, amount, before, null);
            case WITHDRAW -> new ContainerWithdrawRecord(
                    support.newId(), 1, "shulker-withdraw", occurred,
                    support.expiresAt(occurred),
                    support.playerOrigin(), support.playerSource(player),
                    location, slotItem == null ? "UNKNOWN" : slotItem.getType().name(),
                    containerType, slot, amount, before, null);
        };
        return Stream.of(record);
    }

}
