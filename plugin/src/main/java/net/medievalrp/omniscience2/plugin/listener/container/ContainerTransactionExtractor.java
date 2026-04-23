package net.medievalrp.omniscience2.plugin.listener.container;

import java.time.Instant;
import java.util.stream.Stream;
import net.medievalrp.omniscience2.api.event.ContainerDepositRecord;
import net.medievalrp.omniscience2.api.event.ContainerWithdrawRecord;
import net.medievalrp.omniscience2.api.event.EventRecord;
import net.medievalrp.omniscience2.api.event.StoredItem;
import net.medievalrp.omniscience2.api.extension.EventExtractor;
import net.medievalrp.omniscience2.api.util.BlockLocation;
import net.medievalrp.omniscience2.plugin.listener.ExtractorSupport;
import net.medievalrp.omniscience2.plugin.util.BlockLocations;
import net.medievalrp.omniscience2.plugin.util.ItemSerialization;
import org.bukkit.Material;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

public final class ContainerTransactionExtractor implements EventExtractor<InventoryClickEvent, EventRecord> {

    private final ExtractorSupport support;

    public ContainerTransactionExtractor(ExtractorSupport support) {
        this.support = support;
    }

    @Override
    public Class<InventoryClickEvent> eventType() {
        return InventoryClickEvent.class;
    }

    @Override
    public Stream<EventRecord> extract(InventoryClickEvent event) {
        Inventory clicked = event.getClickedInventory();
        if (clicked == null) {
            return Stream.empty();
        }
        InventoryHolder holder = clicked.getHolder();
        if (!(holder instanceof Container container)) {
            return Stream.empty();
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            return Stream.empty();
        }

        InventoryAction action = event.getAction();
        Direction direction = directionOf(action);
        if (direction == null) {
            return Stream.empty();
        }

        int slot = event.getSlot();
        ItemStack slotItem = clicked.getItem(slot);
        ItemStack cursor = event.getCursor();
        int amount = amountOf(action, slotItem, cursor);
        if (amount <= 0) {
            return Stream.empty();
        }

        BlockLocation location = BlockLocations.fromLocation(container.getBlock().getLocation());
        String containerType = container.getBlock().getType().name();
        StoredItem before = ItemSerialization.storedItem(slot, slotItem);
        Instant occurred = support.now();

        EventRecord record = switch (direction) {
            case DEPOSIT -> {
                String target = cursor == null ? "UNKNOWN" : cursor.getType().name();
                yield new ContainerDepositRecord(
                        support.newId(),
                        1,
                        "deposit",
                        occurred,
                        support.expiresAt(occurred),
                        support.playerOrigin(),
                        support.playerSource(player),
                        location,
                        target,
                        containerType,
                        slot,
                        amount,
                        before,
                        null);
            }
            case WITHDRAW -> {
                String target = slotItem == null ? "UNKNOWN" : slotItem.getType().name();
                yield new ContainerWithdrawRecord(
                        support.newId(),
                        1,
                        "withdraw",
                        occurred,
                        support.expiresAt(occurred),
                        support.playerOrigin(),
                        support.playerSource(player),
                        location,
                        target,
                        containerType,
                        slot,
                        amount,
                        before,
                        null);
            }
        };
        return Stream.of(record);
    }

    private static Direction directionOf(InventoryAction action) {
        return switch (action) {
            case PLACE_ALL, PLACE_ONE, PLACE_SOME -> Direction.DEPOSIT;
            case PICKUP_ALL, PICKUP_HALF, PICKUP_ONE, PICKUP_SOME -> Direction.WITHDRAW;
            default -> null;
        };
    }

    private static int amountOf(InventoryAction action, ItemStack slotItem, ItemStack cursor) {
        return switch (action) {
            case PLACE_ALL -> cursor == null || cursor.getType() == Material.AIR ? 0 : cursor.getAmount();
            case PLACE_ONE -> 1;
            case PLACE_SOME -> {
                if (cursor == null || cursor.getType() == Material.AIR) {
                    yield 0;
                }
                int max = cursor.getType().getMaxStackSize();
                int existing = slotItem == null ? 0 : slotItem.getAmount();
                yield Math.max(0, Math.min(cursor.getAmount(), max - existing));
            }
            case PICKUP_ALL -> slotItem == null ? 0 : slotItem.getAmount();
            case PICKUP_HALF -> slotItem == null ? 0 : (slotItem.getAmount() + 1) / 2;
            case PICKUP_ONE -> 1;
            case PICKUP_SOME -> slotItem == null ? 0 : slotItem.getAmount();
            default -> 0;
        };
    }

    private enum Direction {
        DEPOSIT,
        WITHDRAW
    }
}
