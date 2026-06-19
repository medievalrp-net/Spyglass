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
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Chest;
import org.bukkit.block.Container;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.minecart.HopperMinecart;
import org.bukkit.entity.minecart.StorageMinecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

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
        if (holder instanceof ShulkerBox) {
            return;
        }
        // The clicked slot indexes into the (possibly combined) inventory the
        // player has open; resolveSlotTarget maps it back to the physical
        // block + that block's local slot. For a double chest the holder is a
        // DoubleChest (not a Container), and the 54-slot view spans two blocks
        // — resolving here is what makes double-chest deposits record at all.
        int rawSlot = event.getSlot();
        SlotTarget resolved = resolveSlotTarget(holder, rawSlot);
        if (resolved == null) {
            return;
        }
        ContainerTarget containerTarget = resolved.target();
        // Block-local slot: what the single-block rollback path expects.
        int slot = resolved.slot();
        // Reads use the RAW slot (the combined inventory the player clicked);
        // records use the block-local slot so (location, slot) round-trips.
        ItemStack slotItem = clicked.getItem(rawSlot);
        ItemStack cursor = event.getCursor();

        if (action == InventoryAction.SWAP_WITH_CURSOR) {
            handleSwap(containerTarget, player, slot, slotItem, cursor, occurred);
            return;
        }

        if (action == InventoryAction.HOTBAR_SWAP || action == InventoryAction.HOTBAR_MOVE_AND_READD) {
            handleHotbarSwap(event, containerTarget, player, slot, slotItem, occurred);
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

        BlockLocation location = containerTarget.location();
        String containerType = containerTarget.type();
        StoredItem before = ItemSerialization.storedItem(slot, slotItem);

        // (before, after) must be the SLOT's exact state pair — the
        // rollback effect writes `before` back only when the live slot
        // still matches `after`. MONITOR fires before the click applies,
        // so `after` is computed from the action (#28; a hardcoded null
        // here made every deposit rollback skip on "slot changed").
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
                StoredItem after = ItemSerialization.storedItem(slot, afterStack);
                String target = cursor == null ? "UNKNOWN" : cursor.getType().name();
                recorder.record(new ContainerDepositRecord(
                        support.newId(), "deposit", occurred,
                        support.expiresAt(occurred),
                        support.playerOrigin(), support.playerSource(player),
                        location, support.serverName(), target, containerType, slot, amount, before, after));
            }
            case WITHDRAW -> {
                ItemStack afterStack = null;
                if (slotItem != null && slotItem.getType() != Material.AIR
                        && slotItem.getAmount() > amount) {
                    afterStack = slotItem.clone();
                    afterStack.setAmount(slotItem.getAmount() - amount);
                }
                StoredItem after = ItemSerialization.storedItem(slot, afterStack);
                String target = slotItem == null ? "UNKNOWN" : slotItem.getType().name();
                recorder.record(new ContainerWithdrawRecord(
                        support.newId(), "withdraw", occurred,
                        support.expiresAt(occurred),
                        support.playerOrigin(), support.playerSource(player),
                        location, support.serverName(), target, containerType, slot, amount, before, after));
            }
        }
    }

    private void handleMoveToOther(InventoryClickEvent event, Player player,
                                   Inventory clicked, Instant occurred) {
        Inventory top = event.getView().getTopInventory();
        Inventory bottom = event.getView().getBottomInventory();
        boolean clickedIsTop = clicked.equals(top);

        InventoryHolder topHolder = top.getHolder();
        if (topHolder instanceof ShulkerBox) {
            return;
        }
        // Withdraw shift-clicks index the container (top) inventory, so the
        // clicked slot is a container slot to translate. Deposit shift-clicks
        // index the player (bottom) inventory and record slot -1, so resolve
        // with -1 to pin a stable half and never mistranslate a player slot.
        int containerSlot = clickedIsTop ? event.getSlot() : -1;
        SlotTarget resolved = resolveSlotTarget(topHolder, containerSlot);
        if (resolved == null) {
            return;
        }
        ContainerTarget containerTarget = resolved.target();

        ItemStack moved = clicked.getItem(event.getSlot());
        if (moved == null || moved.getType() == Material.AIR) {
            return;
        }
        int amount = moved.getAmount();
        BlockLocation location = containerTarget.location();
        String containerType = containerTarget.type();

        if (clickedIsTop) {
            // Shift-click from container to player inventory -> withdraw.
            int recordSlot = resolved.slot();
            StoredItem before = ItemSerialization.storedItem(recordSlot, moved);
            recorder.record(new ContainerWithdrawRecord(
                    support.newId(), "withdraw", occurred,
                    support.expiresAt(occurred),
                    support.playerOrigin(), support.playerSource(player),
                    location, support.serverName(), moved.getType().name(), containerType, recordSlot, amount, before, null));
            return;
        }
        if (!clicked.equals(bottom)) {
            return;
        }
        // Shift-click from player inventory to container -> deposit. Slot is
        // -1 (no specific container slot); `before` carries the source
        // player-inventory slot, matching the single-chest behavior.
        StoredItem before = ItemSerialization.storedItem(event.getSlot(), moved);
        recorder.record(new ContainerDepositRecord(
                support.newId(), "deposit", occurred,
                support.expiresAt(occurred),
                support.playerOrigin(), support.playerSource(player),
                location, support.serverName(), moved.getType().name(), containerType, -1, amount, null, before));
    }

    private void handleHotbarSwap(InventoryClickEvent event, ContainerTarget containerTarget, Player player,
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
        BlockLocation location = containerTarget.location();
        String containerType = containerTarget.type();
        // A hotbar swap replaces the container slot's contents with the
        // hotbar stack (or empties it): one (before=slotItem,
        // after=hotbarItem) state pair shared by both records (#28).
        StoredItem beforePair = slotHadItem ? ItemSerialization.storedItem(slot, slotItem) : null;
        StoredItem afterPair = hotbarHadItem ? ItemSerialization.storedItem(slot, hotbarItem) : null;
        if (slotHadItem) {
            recorder.record(new ContainerWithdrawRecord(
                    support.newId(), "withdraw", occurred,
                    support.expiresAt(occurred),
                    support.playerOrigin(), support.playerSource(player),
                    location, support.serverName(), slotItem.getType().name(), containerType, slot,
                    slotItem.getAmount(), beforePair, afterPair));
        }
        if (hotbarHadItem) {
            recorder.record(new ContainerDepositRecord(
                    support.newId(), "deposit", occurred,
                    support.expiresAt(occurred),
                    support.playerOrigin(), support.playerSource(player),
                    location, support.serverName(), hotbarItem.getType().name(), containerType, slot,
                    hotbarItem.getAmount(), beforePair, afterPair));
        }
    }

    private void handleSwap(ContainerTarget containerTarget, Player player, int slot,
                            ItemStack slotItem, ItemStack cursor, Instant occurred) {
        BlockLocation location = containerTarget.location();
        String containerType = containerTarget.type();
        boolean hadSlotItem = slotItem != null && slotItem.getType() != Material.AIR;
        boolean hadCursorItem = cursor != null && cursor.getType() != Material.AIR;
        if (!hadSlotItem && !hadCursorItem) {
            return;
        }
        if (hadSlotItem) {
            recorder.record(new ContainerWithdrawRecord(
                    support.newId(), "withdraw", occurred,
                    support.expiresAt(occurred),
                    support.playerOrigin(), support.playerSource(player),
                    location, support.serverName(), slotItem.getType().name(), containerType, slot, slotItem.getAmount(),
                    ItemSerialization.storedItem(slot, slotItem),
                    hadCursorItem ? ItemSerialization.storedItem(slot, cursor) : null));
        }
        if (hadCursorItem) {
            recorder.record(new ContainerDepositRecord(
                    support.newId(), "deposit", occurred,
                    support.expiresAt(occurred),
                    support.playerOrigin(), support.playerSource(player),
                    location, support.serverName(), cursor.getType().name(), containerType, slot, cursor.getAmount(),
                    hadSlotItem ? ItemSerialization.storedItem(slot, slotItem) : null,
                    ItemSerialization.storedItem(slot, cursor)));
        }
    }

    /**
     * Resolve the physical block + block-local slot for a click in a
     * container inventory.
     *
     * <p>Double chests are the reason this exists: their holder is a
     * {@link DoubleChest} (which does <em>not</em> implement
     * {@link Container}, so {@link #resolveTarget} alone drops them), and
     * they present one 54-slot inventory spanning two blocks. The clicked
     * slot is mapped back to the half it belongs to and re-based into that
     * half's 0-26 range so the recorded {@code (location, slot)} round-trips
     * through the single-block rollback path. A slot {@code < 0} (slotless
     * deposit) pins the left half.
     *
     * <p>Every other holder delegates to {@link #resolveTarget} with the
     * slot unchanged, so single chests, barrels and minecarts behave exactly
     * as before.
     */
    private static @Nullable SlotTarget resolveSlotTarget(@Nullable InventoryHolder holder, int slot) {
        if (holder instanceof DoubleChest doubleChest) {
            if (!(doubleChest.getLeftSide() instanceof Chest left)
                    || !(doubleChest.getRightSide() instanceof Chest right)) {
                return null;
            }
            int leftSize = left.getBlockInventory().getSize();
            Chest half = slot >= leftSize ? right : left;
            int localSlot = slot >= leftSize ? slot - leftSize : slot;
            ContainerTarget target = new ContainerTarget(
                    BlockLocations.fromLocation(half.getBlock().getLocation()),
                    half.getBlock().getType().name());
            return new SlotTarget(target, localSlot);
        }
        ContainerTarget base = resolveTarget(holder);
        return base == null ? null : new SlotTarget(base, slot);
    }

    /**
     * Resolve any inventory holder we care about into a uniform
     * (BlockLocation, type-name) pair so the deposit / withdraw flow
     * doesn't have to special-case minecart inventories vs block
     * containers all over the place. Returns null for holders we
     * don't track (player inventory, anvils, brewing stands, etc.).
     */
    private static @Nullable ContainerTarget resolveTarget(@Nullable InventoryHolder holder) {
        if (holder instanceof Container blockContainer) {
            Location loc = blockContainer.getBlock().getLocation();
            return new ContainerTarget(BlockLocations.fromLocation(loc),
                    blockContainer.getBlock().getType().name());
        }
        // Storage / hopper minecarts use the entity's current location
        // — they're moving inventories, so the recorded location is a
        // snapshot at deposit/withdraw time.
        if (holder instanceof StorageMinecart cart) {
            return entityTarget(cart);
        }
        if (holder instanceof HopperMinecart cart) {
            return entityTarget(cart);
        }
        return null;
    }

    private static ContainerTarget entityTarget(Entity entity) {
        return new ContainerTarget(
                BlockLocations.fromLocation(entity.getLocation()),
                entity.getType().name());
    }

    private record ContainerTarget(BlockLocation location, String type) {
    }

    /** A resolved container target paired with the block-local slot the
     *  click maps to (identical to the raw slot for everything but double
     *  chests). */
    private record SlotTarget(ContainerTarget target, int slot) {
    }
}
