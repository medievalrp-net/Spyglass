package net.medievalrp.spyglass.plugin.listener.container;

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
import org.bukkit.block.Container;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class ContainerTransactionListener implements RecordingListener {

    private final Recorder recorder;
    private final RecordingSupport support;

    public ContainerTransactionListener(Recorder recorder, RecordingSupport support) {
        this.recorder = recorder;
        this.support = support;
    }

    @Override
    public Set<String> events() {
        return Set.of("deposit", "withdraw");
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

        // MOVE_TO_OTHER_INVENTORY: the item moves from the clicked inventory to the opposite.
        // If the clicked inventory is the player's and the top inventory is a container, the
        // item moves INTO the container -> deposit. Otherwise, if the clicked inventory is
        // the container and the opposite is the player's, the item moves OUT -> withdraw.
        if (action == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
            handleMoveToOther(event, player, clicked, occurred);
            return;
        }

        InventoryHolder holder = clicked.getHolder();
        if (!(holder instanceof Container container)) {
            return;
        }
        if (holder instanceof ShulkerBox) {
            return;
        }

        int slot = event.getSlot();
        ItemStack slotItem = clicked.getItem(slot);
        ItemStack cursor = event.getCursor();

        if (action == InventoryAction.SWAP_WITH_CURSOR) {
            handleSwap(container, player, slot, slotItem, cursor, occurred);
            return;
        }

        if (action == InventoryAction.HOTBAR_SWAP || action == InventoryAction.HOTBAR_MOVE_AND_READD) {
            handleHotbarSwap(event, container, player, slot, slotItem, occurred);
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

        BlockLocation location = BlockLocations.fromLocation(container.getBlock().getLocation());
        String containerType = container.getBlock().getType().name();
        StoredItem before = ItemSerialization.storedItem(slot, slotItem);

        switch (direction) {
            case DEPOSIT -> {
                String target = cursor == null ? "UNKNOWN" : cursor.getType().name();
                recorder.record(new ContainerDepositRecord(
                        support.newId(), 1, "deposit", occurred,
                        support.expiresAt(occurred),
                        support.playerOrigin(), support.playerSource(player),
                        location, target, containerType, slot, amount, before, null));
            }
            case WITHDRAW -> {
                String target = slotItem == null ? "UNKNOWN" : slotItem.getType().name();
                recorder.record(new ContainerWithdrawRecord(
                        support.newId(), 1, "withdraw", occurred,
                        support.expiresAt(occurred),
                        support.playerOrigin(), support.playerSource(player),
                        location, target, containerType, slot, amount, before, null));
            }
        }
    }

    private void handleMoveToOther(InventoryClickEvent event, Player player,
                                   Inventory clicked, Instant occurred) {
        Inventory top = event.getView().getTopInventory();
        Inventory bottom = event.getView().getBottomInventory();
        boolean clickedIsTop = clicked.equals(top);

        InventoryHolder topHolder = top.getHolder();
        if (!(topHolder instanceof Container container)) {
            return;
        }
        if (topHolder instanceof ShulkerBox) {
            return;
        }

        ItemStack moved = clicked.getItem(event.getSlot());
        if (moved == null || moved.getType() == Material.AIR) {
            return;
        }
        int amount = moved.getAmount();
        BlockLocation location = BlockLocations.fromLocation(container.getBlock().getLocation());
        String containerType = container.getBlock().getType().name();
        StoredItem before = ItemSerialization.storedItem(event.getSlot(), moved);

        if (clickedIsTop) {
            // Shift-click from container to player inventory -> withdraw.
            recorder.record(new ContainerWithdrawRecord(
                    support.newId(), 1, "withdraw", occurred,
                    support.expiresAt(occurred),
                    support.playerOrigin(), support.playerSource(player),
                    location, moved.getType().name(), containerType, event.getSlot(), amount, before, null));
            return;
        }
        if (!clicked.equals(bottom)) {
            return;
        }
        // Shift-click from player inventory to container -> deposit.
        recorder.record(new ContainerDepositRecord(
                support.newId(), 1, "deposit", occurred,
                support.expiresAt(occurred),
                support.playerOrigin(), support.playerSource(player),
                location, moved.getType().name(), containerType, -1, amount, null, before));
    }

    private void handleHotbarSwap(InventoryClickEvent event, Container container, Player player,
                                  int slot, ItemStack slotItem, Instant occurred) {
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
        BlockLocation location = BlockLocations.fromLocation(container.getBlock().getLocation());
        String containerType = container.getBlock().getType().name();
        if (slotHadItem) {
            StoredItem stored = ItemSerialization.storedItem(slot, slotItem);
            recorder.record(new ContainerWithdrawRecord(
                    support.newId(), 1, "withdraw", occurred,
                    support.expiresAt(occurred),
                    support.playerOrigin(), support.playerSource(player),
                    location, slotItem.getType().name(), containerType, slot,
                    slotItem.getAmount(), stored, null));
        }
        if (hotbarHadItem) {
            StoredItem stored = ItemSerialization.storedItem(slot, hotbarItem);
            recorder.record(new ContainerDepositRecord(
                    support.newId(), 1, "deposit", occurred,
                    support.expiresAt(occurred),
                    support.playerOrigin(), support.playerSource(player),
                    location, hotbarItem.getType().name(), containerType, slot,
                    hotbarItem.getAmount(), null, stored));
        }
    }

    private void handleSwap(Container container, Player player, int slot,
                            ItemStack slotItem, ItemStack cursor, Instant occurred) {
        BlockLocation location = BlockLocations.fromLocation(container.getBlock().getLocation());
        String containerType = container.getBlock().getType().name();
        boolean hadSlotItem = slotItem != null && slotItem.getType() != Material.AIR;
        boolean hadCursorItem = cursor != null && cursor.getType() != Material.AIR;
        if (!hadSlotItem && !hadCursorItem) {
            return;
        }
        if (hadSlotItem) {
            recorder.record(new ContainerWithdrawRecord(
                    support.newId(), 1, "withdraw", occurred,
                    support.expiresAt(occurred),
                    support.playerOrigin(), support.playerSource(player),
                    location, slotItem.getType().name(), containerType, slot, slotItem.getAmount(),
                    ItemSerialization.storedItem(slot, slotItem),
                    hadCursorItem ? ItemSerialization.storedItem(slot, cursor) : null));
        }
        if (hadCursorItem) {
            recorder.record(new ContainerDepositRecord(
                    support.newId(), 1, "deposit", occurred,
                    support.expiresAt(occurred),
                    support.playerOrigin(), support.playerSource(player),
                    location, cursor.getType().name(), containerType, slot, cursor.getAmount(),
                    hadSlotItem ? ItemSerialization.storedItem(slot, slotItem) : null,
                    ItemSerialization.storedItem(slot, cursor)));
        }
    }
}
