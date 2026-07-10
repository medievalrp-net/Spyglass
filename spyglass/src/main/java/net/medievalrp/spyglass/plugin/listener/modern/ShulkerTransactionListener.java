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
    // Main-thread, next-tick: shift-click deposits are diffed after the
    // click applies, per-slot, exactly like the chest listener (#268).
    private final java.util.concurrent.Executor nextTick;

    public ShulkerTransactionListener(Recorder recorder, RecordingSupport support,
                                      java.util.concurrent.Executor nextTick) {
        this.recorder = recorder;
        this.support = support;
        this.nextTick = nextTick;
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

        // (before, after) must be the SLOT's exact state pair - the
        // rollback effect writes `before` back only when the live slot
        // still matches `after`. MONITOR fires before the click applies,
        // so `after` is computed from the action, same as the chest
        // listener (#28). The hardcoded null this path used to carry
        // made every shulker reversal skip on "slot changed" or write
        // nothing while claiming applied (#298).
        switch (direction) {
            case DEPOSIT -> {
                ItemStack afterStack;
                if (slotItem == null || slotItem.getType() == Material.AIR) {
                    afterStack = cursor == null ? null : cursor.clone();
                    if (afterStack != null) {
                        afterStack.setAmount(amount);
                    }
                } else {
                    afterStack = slotItem.clone();
                    afterStack.setAmount(Math.min(slotItem.getMaxStackSize(),
                            slotItem.getAmount() + amount));
                }
                recorder.record(new ContainerDepositRecord(
                        support.newId(), "shulker-deposit", occurred,
                        support.expiresAt(occurred),
                        support.playerOrigin(), support.playerSource(player),
                        location, support.serverName(),
                        cursor == null ? "UNKNOWN" : cursor.getType().name(),
                        containerType, slot, amount, before,
                        ItemSerialization.storedItem(slot, afterStack)));
            }
            case WITHDRAW -> {
                ItemStack afterStack = null;
                if (slotItem != null && slotItem.getType() != Material.AIR
                        && slotItem.getAmount() > amount) {
                    afterStack = slotItem.clone();
                    afterStack.setAmount(slotItem.getAmount() - amount);
                }
                recorder.record(new ContainerWithdrawRecord(
                        support.newId(), "shulker-withdraw", occurred,
                        support.expiresAt(occurred),
                        support.playerOrigin(), support.playerSource(player),
                        location, support.serverName(),
                        slotItem == null ? "UNKNOWN" : slotItem.getType().name(),
                        containerType, slot, amount, before,
                        ItemSerialization.storedItem(slot, afterStack)));
            }
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
                    support.newId(), "shulker-deposit", occurred,
                    support.expiresAt(occurred),
                    support.playerOrigin(), support.playerSource(player),
                    location, support.serverName(), deposited.getType().name(), containerType,
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
                    support.newId(), "shulker-withdraw", occurred,
                    support.expiresAt(occurred),
                    support.playerOrigin(), support.playerSource(player),
                    location, support.serverName(), moved.getType().name(), containerType,
                    event.getSlot(), amount, before, null));
            return;
        }
        if (!clicked.equals(bottom)) {
            return;
        }
        // Shift-click from player inventory into the shulker. The event
        // names no destination slot - vanilla merges into partial stacks
        // first, then empty slots, so one click can touch several slots.
        // Snapshot the top inventory now (MONITOR fires before the click
        // applies) and diff it one tick later, one record per slot -
        // parity with the chest listener's #268 fix. The old single
        // record carried slot -1, which every rollback path rejects.
        int topSize = top.getSize();
        ItemStack[] beforeTop = new ItemStack[topSize];
        for (int i = 0; i < topSize; i++) {
            ItemStack item = top.getItem(i);
            beforeTop[i] = item == null ? null : item.clone();
        }
        Material movedType = moved.getType();
        nextTick.execute(() -> {
            for (int slot = 0; slot < topSize; slot++) {
                ItemStack now = top.getItem(slot);
                if (now == null || now.getType() != movedType) {
                    continue;
                }
                ItemStack was = beforeTop[slot];
                int wasAmount = was == null || was.getType() == Material.AIR ? 0 : was.getAmount();
                int delta = now.getAmount() - wasAmount;
                if (delta <= 0) {
                    continue;
                }
                recorder.record(new ContainerDepositRecord(
                        support.newId(), "shulker-deposit", occurred,
                        support.expiresAt(occurred),
                        support.playerOrigin(), support.playerSource(player),
                        location, support.serverName(), movedType.name(), containerType,
                        slot, delta,
                        wasAmount > 0 ? ItemSerialization.storedItem(slot, was) : null,
                        ItemSerialization.storedItem(slot, now)));
            }
        });
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
                    support.newId(), "shulker-withdraw", occurred,
                    support.expiresAt(occurred),
                    support.playerOrigin(), support.playerSource(player),
                    location, support.serverName(), slotItem.getType().name(), containerType, slot,
                    slotItem.getAmount(), stored, null));
        }
        if (hotbarHadItem) {
            StoredItem stored = ItemSerialization.storedItem(slot, hotbarItem);
            recorder.record(new ContainerDepositRecord(
                    support.newId(), "shulker-deposit", occurred,
                    support.expiresAt(occurred),
                    support.playerOrigin(), support.playerSource(player),
                    location, support.serverName(), hotbarItem.getType().name(), containerType, slot,
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
                    support.newId(), "shulker-withdraw", occurred,
                    support.expiresAt(occurred),
                    support.playerOrigin(), support.playerSource(player),
                    location, support.serverName(), slotItem.getType().name(), containerType, slot, slotItem.getAmount(),
                    ItemSerialization.storedItem(slot, slotItem),
                    hadCursorItem ? ItemSerialization.storedItem(slot, cursor) : null));
        }
        if (hadCursorItem) {
            recorder.record(new ContainerDepositRecord(
                    support.newId(), "shulker-deposit", occurred,
                    support.expiresAt(occurred),
                    support.playerOrigin(), support.playerSource(player),
                    location, support.serverName(), cursor.getType().name(), containerType, slot, cursor.getAmount(),
                    hadSlotItem ? ItemSerialization.storedItem(slot, slotItem) : null,
                    ItemSerialization.storedItem(slot, cursor)));
        }
    }
}
