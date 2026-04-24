package net.medievalrp.spyglass.plugin.listener.modern;

import java.time.Instant;
import java.util.Set;
import net.medievalrp.spyglass.api.event.ContainerDepositRecord;
import net.medievalrp.spyglass.api.event.ContainerWithdrawRecord;
import net.medievalrp.spyglass.api.event.StoredItem;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.plugin.listener.RecordingSupport;
import net.medievalrp.spyglass.plugin.listener.RecordingListener;
import net.medievalrp.spyglass.plugin.pipeline.Recorder;
import net.medievalrp.spyglass.plugin.util.BlockLocations;
import net.medievalrp.spyglass.plugin.util.InventoryActions;
import net.medievalrp.spyglass.plugin.util.InventoryActions.Direction;
import net.medievalrp.spyglass.plugin.util.ItemSerialization;
import org.bukkit.Material;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class ShulkerTransactionListener implements RecordingListener {

    private final Recorder recorder;
    private final RecordingSupport support;

    public ShulkerTransactionListener(Recorder recorder, RecordingSupport support) {
        this.recorder = recorder;
        this.support = support;
    }

    @Override
    public Set<String> events() {
        return Set.of("shulker-deposit", "shulker-withdraw");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        Inventory clicked = event.getClickedInventory();
        if (clicked == null) {
            return;
        }

        InventoryAction action = event.getAction();
        Instant occurred = support.now();

        if (action == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
            handleMoveToOther(event, player, clicked, occurred);
            return;
        }

        InventoryHolder holder = clicked.getHolder();
        if (!(holder instanceof ShulkerBox shulker)) {
            return;
        }

        int slot = event.getSlot();
        ItemStack slotItem = clicked.getItem(slot);
        ItemStack cursor = event.getCursor();
        BlockLocation location = BlockLocations.fromLocation(shulker.getBlock().getLocation());
        String containerType = shulker.getBlock().getType().name();

        if (action == InventoryAction.SWAP_WITH_CURSOR) {
            handleSwap(shulker, player, slot, slotItem, cursor, location, containerType, occurred);
            return;
        }
        if (action == InventoryAction.HOTBAR_SWAP || action == InventoryAction.HOTBAR_MOVE_AND_READD) {
            handleHotbarSwap(event, player, slot, slotItem, location, containerType, occurred);
            return;
        }

        Direction direction = InventoryActions.directionOf(action);
        if (direction == null) {
            return;
        }
        int amount = InventoryActions.amountOf(action, slotItem, cursor);
        if (amount <= 0) {
            return;
        }
        StoredItem before = ItemSerialization.storedItem(slot, slotItem);

        switch (direction) {
            case DEPOSIT -> recorder.record(new ContainerDepositRecord(
                    support.newId(), 1, "shulker-deposit", occurred,
                    support.expiresAt(occurred),
                    support.playerOrigin(), support.playerSource(player),
                    location, cursor == null ? "UNKNOWN" : cursor.getType().name(),
                    containerType, slot, amount, before, null));
            case WITHDRAW -> recorder.record(new ContainerWithdrawRecord(
                    support.newId(), 1, "shulker-withdraw", occurred,
                    support.expiresAt(occurred),
                    support.playerOrigin(), support.playerSource(player),
                    location, slotItem == null ? "UNKNOWN" : slotItem.getType().name(),
                    containerType, slot, amount, before, null));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        Inventory top = event.getView().getTopInventory();
        InventoryHolder holder = top.getHolder();
        if (!(holder instanceof ShulkerBox shulker)) {
            return;
        }
        int topSize = top.getSize();
        BlockLocation location = BlockLocations.fromLocation(shulker.getBlock().getLocation());
        String containerType = shulker.getBlock().getType().name();
        Instant occurred = support.now();
        for (java.util.Map.Entry<Integer, ItemStack> entry : event.getNewItems().entrySet()) {
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
            recorder.record(new ContainerDepositRecord(
                    support.newId(), 1, "shulker-deposit", occurred,
                    support.expiresAt(occurred),
                    support.playerOrigin(), support.playerSource(player),
                    location, deposited.getType().name(), containerType,
                    rawSlot, delta, storedBefore, storedAfter));
        }
    }

    private void handleMoveToOther(InventoryClickEvent event, Player player,
                                   Inventory clicked, Instant occurred) {
        Inventory top = event.getView().getTopInventory();
        Inventory bottom = event.getView().getBottomInventory();
        InventoryHolder topHolder = top.getHolder();
        if (!(topHolder instanceof ShulkerBox shulker)) {
            return;
        }
        ItemStack moved = clicked.getItem(event.getSlot());
        if (moved == null || moved.getType() == Material.AIR) {
            return;
        }
        int amount = moved.getAmount();
        BlockLocation location = BlockLocations.fromLocation(shulker.getBlock().getLocation());
        String containerType = shulker.getBlock().getType().name();
        StoredItem before = ItemSerialization.storedItem(event.getSlot(), moved);
        boolean clickedIsTop = clicked.equals(top);

        if (clickedIsTop) {
            recorder.record(new ContainerWithdrawRecord(
                    support.newId(), 1, "shulker-withdraw", occurred,
                    support.expiresAt(occurred),
                    support.playerOrigin(), support.playerSource(player),
                    location, moved.getType().name(), containerType,
                    event.getSlot(), amount, before, null));
            return;
        }
        if (!clicked.equals(bottom)) {
            return;
        }
        recorder.record(new ContainerDepositRecord(
                support.newId(), 1, "shulker-deposit", occurred,
                support.expiresAt(occurred),
                support.playerOrigin(), support.playerSource(player),
                location, moved.getType().name(), containerType,
                -1, amount, null, before));
    }

    private void handleHotbarSwap(InventoryClickEvent event, Player player, int slot,
                                  ItemStack slotItem, BlockLocation location,
                                  String containerType, Instant occurred) {
        int hotbarButton = event.getHotbarButton();
        if (hotbarButton < 0) {
            return;
        }
        ItemStack hotbarItem = player.getInventory().getItem(hotbarButton);
        boolean slotHadItem = slotItem != null && slotItem.getType() != Material.AIR;
        boolean hotbarHadItem = hotbarItem != null && hotbarItem.getType() != Material.AIR;
        if (!slotHadItem && !hotbarHadItem) {
            return;
        }
        if (slotHadItem) {
            StoredItem stored = ItemSerialization.storedItem(slot, slotItem);
            recorder.record(new ContainerWithdrawRecord(
                    support.newId(), 1, "shulker-withdraw", occurred,
                    support.expiresAt(occurred),
                    support.playerOrigin(), support.playerSource(player),
                    location, slotItem.getType().name(), containerType, slot,
                    slotItem.getAmount(), stored, null));
        }
        if (hotbarHadItem) {
            StoredItem stored = ItemSerialization.storedItem(slot, hotbarItem);
            recorder.record(new ContainerDepositRecord(
                    support.newId(), 1, "shulker-deposit", occurred,
                    support.expiresAt(occurred),
                    support.playerOrigin(), support.playerSource(player),
                    location, hotbarItem.getType().name(), containerType, slot,
                    hotbarItem.getAmount(), null, stored));
        }
    }

    private void handleSwap(ShulkerBox shulker, Player player, int slot,
                            ItemStack slotItem, ItemStack cursor,
                            BlockLocation location, String containerType, Instant occurred) {
        boolean hadSlotItem = slotItem != null && slotItem.getType() != Material.AIR;
        boolean hadCursorItem = cursor != null && cursor.getType() != Material.AIR;
        if (!hadSlotItem && !hadCursorItem) {
            return;
        }
        if (hadSlotItem) {
            recorder.record(new ContainerWithdrawRecord(
                    support.newId(), 1, "shulker-withdraw", occurred,
                    support.expiresAt(occurred),
                    support.playerOrigin(), support.playerSource(player),
                    location, slotItem.getType().name(), containerType, slot, slotItem.getAmount(),
                    ItemSerialization.storedItem(slot, slotItem),
                    hadCursorItem ? ItemSerialization.storedItem(slot, cursor) : null));
        }
        if (hadCursorItem) {
            recorder.record(new ContainerDepositRecord(
                    support.newId(), 1, "shulker-deposit", occurred,
                    support.expiresAt(occurred),
                    support.playerOrigin(), support.playerSource(player),
                    location, cursor.getType().name(), containerType, slot, cursor.getAmount(),
                    hadSlotItem ? ItemSerialization.storedItem(slot, slotItem) : null,
                    ItemSerialization.storedItem(slot, cursor)));
        }
    }
}
