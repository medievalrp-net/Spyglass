package net.medievalrp.spyglass.plugin.listener.container;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import net.medievalrp.spyglass.api.event.ContainerDepositRecord;
import net.medievalrp.spyglass.api.event.ContainerWithdrawRecord;
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
        if (!(event.getWhoClicked() instanceof Player player)) {
            return Stream.empty();
        }
        Inventory clicked = event.getClickedInventory();
        if (clicked == null) {
            return Stream.empty();
        }

        InventoryAction action = event.getAction();
        Instant occurred = support.now();

        // MOVE_TO_OTHER_INVENTORY: the item moves from the clicked inventory to the opposite.
        // If the clicked inventory is the player's and the top inventory is a container, the
        // item moves INTO the container -> deposit. Otherwise, if the clicked inventory is
        // the container and the opposite is the player's, the item moves OUT -> withdraw.
        if (action == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
            return handleMoveToOther(event, player, clicked, occurred).stream();
        }

        InventoryHolder holder = clicked.getHolder();
        if (!(holder instanceof Container container)) {
            return Stream.empty();
        }
        if (holder instanceof ShulkerBox) {
            return Stream.empty();
        }

        int slot = event.getSlot();
        ItemStack slotItem = clicked.getItem(slot);
        ItemStack cursor = event.getCursor();

        if (action == InventoryAction.SWAP_WITH_CURSOR) {
            return handleSwap(container, player, slot, slotItem, cursor, occurred).stream();
        }

        if (action == InventoryAction.HOTBAR_SWAP || action == InventoryAction.HOTBAR_MOVE_AND_READD) {
            return handleHotbarSwap(event, container, player, slot, slotItem, occurred).stream();
        }

        Direction direction = directionOf(action);
        if (direction == null) {
            return Stream.empty();
        }
        int amount = amountOf(action, slotItem, cursor);
        if (amount <= 0) {
            return Stream.empty();
        }

        BlockLocation location = BlockLocations.fromLocation(container.getBlock().getLocation());
        String containerType = container.getBlock().getType().name();
        StoredItem before = ItemSerialization.storedItem(slot, slotItem);

        EventRecord record = switch (direction) {
            case DEPOSIT -> {
                String target = cursor == null ? "UNKNOWN" : cursor.getType().name();
                yield new ContainerDepositRecord(
                        support.newId(), 1, "deposit", occurred,
                        support.expiresAt(occurred),
                        support.playerOrigin(), support.playerSource(player),
                        location, target, containerType, slot, amount, before, null);
            }
            case WITHDRAW -> {
                String target = slotItem == null ? "UNKNOWN" : slotItem.getType().name();
                yield new ContainerWithdrawRecord(
                        support.newId(), 1, "withdraw", occurred,
                        support.expiresAt(occurred),
                        support.playerOrigin(), support.playerSource(player),
                        location, target, containerType, slot, amount, before, null);
            }
        };
        return Stream.of(record);
    }

    private List<EventRecord> handleMoveToOther(InventoryClickEvent event, Player player,
                                                Inventory clicked, Instant occurred) {
        Inventory top = event.getView().getTopInventory();
        Inventory bottom = event.getView().getBottomInventory();
        boolean clickedIsTop = clicked.equals(top);

        InventoryHolder topHolder = top.getHolder();
        if (!(topHolder instanceof Container container)) {
            return List.of();
        }
        if (topHolder instanceof ShulkerBox) {
            return List.of();
        }

        ItemStack moved = clicked.getItem(event.getSlot());
        if (moved == null || moved.getType() == Material.AIR) {
            return List.of();
        }
        int amount = moved.getAmount();
        BlockLocation location = BlockLocations.fromLocation(container.getBlock().getLocation());
        String containerType = container.getBlock().getType().name();
        StoredItem before = ItemSerialization.storedItem(event.getSlot(), moved);

        if (clickedIsTop) {
            // Shift-click from container to player inventory -> withdraw.
            return List.of(new ContainerWithdrawRecord(
                    support.newId(), 1, "withdraw", occurred,
                    support.expiresAt(occurred),
                    support.playerOrigin(), support.playerSource(player),
                    location, moved.getType().name(), containerType, event.getSlot(), amount, before, null));
        }
        if (!clicked.equals(bottom)) {
            return List.of();
        }
        // Shift-click from player inventory to container -> deposit.
        return List.of(new ContainerDepositRecord(
                support.newId(), 1, "deposit", occurred,
                support.expiresAt(occurred),
                support.playerOrigin(), support.playerSource(player),
                location, moved.getType().name(), containerType, -1, amount, null, before));
    }

    private List<EventRecord> handleHotbarSwap(InventoryClickEvent event, Container container, Player player,
                                                int slot, ItemStack slotItem, Instant occurred) {
        int hotbarButton = event.getHotbarButton();
        if (hotbarButton < 0) {
            return List.of();
        }
        ItemStack hotbarItem = player.getInventory().getItem(hotbarButton);
        boolean slotHadItem = slotItem != null && slotItem.getType() != Material.AIR;
        boolean hotbarHadItem = hotbarItem != null && hotbarItem.getType() != Material.AIR;
        if (!slotHadItem && !hotbarHadItem) {
            return List.of();
        }
        BlockLocation location = BlockLocations.fromLocation(container.getBlock().getLocation());
        String containerType = container.getBlock().getType().name();
        List<EventRecord> records = new ArrayList<>();
        if (slotHadItem) {
            StoredItem stored = ItemSerialization.storedItem(slot, slotItem);
            records.add(new ContainerWithdrawRecord(
                    support.newId(), 1, "withdraw", occurred,
                    support.expiresAt(occurred),
                    support.playerOrigin(), support.playerSource(player),
                    location, slotItem.getType().name(), containerType, slot,
                    slotItem.getAmount(), stored, null));
        }
        if (hotbarHadItem) {
            StoredItem stored = ItemSerialization.storedItem(slot, hotbarItem);
            records.add(new ContainerDepositRecord(
                    support.newId(), 1, "deposit", occurred,
                    support.expiresAt(occurred),
                    support.playerOrigin(), support.playerSource(player),
                    location, hotbarItem.getType().name(), containerType, slot,
                    hotbarItem.getAmount(), null, stored));
        }
        return records;
    }

    private List<EventRecord> handleSwap(Container container, Player player, int slot,
                                          ItemStack slotItem, ItemStack cursor, Instant occurred) {
        BlockLocation location = BlockLocations.fromLocation(container.getBlock().getLocation());
        String containerType = container.getBlock().getType().name();
        boolean hadSlotItem = slotItem != null && slotItem.getType() != Material.AIR;
        boolean hadCursorItem = cursor != null && cursor.getType() != Material.AIR;
        if (!hadSlotItem && !hadCursorItem) {
            return List.of();
        }
        List<EventRecord> records = new ArrayList<>();
        if (hadSlotItem) {
            records.add(new ContainerWithdrawRecord(
                    support.newId(), 1, "withdraw", occurred,
                    support.expiresAt(occurred),
                    support.playerOrigin(), support.playerSource(player),
                    location, slotItem.getType().name(), containerType, slot, slotItem.getAmount(),
                    ItemSerialization.storedItem(slot, slotItem),
                    hadCursorItem ? ItemSerialization.storedItem(slot, cursor) : null));
        }
        if (hadCursorItem) {
            records.add(new ContainerDepositRecord(
                    support.newId(), 1, "deposit", occurred,
                    support.expiresAt(occurred),
                    support.playerOrigin(), support.playerSource(player),
                    location, cursor.getType().name(), containerType, slot, cursor.getAmount(),
                    hadSlotItem ? ItemSerialization.storedItem(slot, slotItem) : null,
                    ItemSerialization.storedItem(slot, cursor)));
        }
        return records;
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
