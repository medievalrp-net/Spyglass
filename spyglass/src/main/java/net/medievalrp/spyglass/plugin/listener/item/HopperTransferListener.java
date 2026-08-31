package net.medievalrp.spyglass.plugin.listener.item;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import net.medievalrp.spyglass.api.event.ContainerDepositRecord;
import net.medievalrp.spyglass.api.event.ContainerWithdrawRecord;
import net.medievalrp.spyglass.api.event.Origin;
import net.medievalrp.spyglass.api.event.Source;
import net.medievalrp.spyglass.api.event.StoredItem;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.plugin.listener.RecordingListener;
import net.medievalrp.spyglass.plugin.listener.RecordingSupport;
import net.medievalrp.spyglass.plugin.pipeline.Recorder;
import net.medievalrp.spyglass.plugin.util.BlockLocations;
import net.medievalrp.spyglass.api.capture.ItemSerialization;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Chest;
import org.bukkit.block.Container;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Logs automated container-to-container item movement by a hopper, dropper or
 * dispenser ({@code InventoryMoveItemEvent}) with per-slot state pairs -
 * {@code transfer-withdraw} on the source and {@code transfer-deposit} on the
 * destination, each carrying the slot's exact (before, after) stacks, so a
 * container snapshot replays hopper traffic exactly instead of flagging the
 * window uncertain.
 *
 * <p><b>Why slots come from the drain tick, not the event.</b> The event
 * names the moved item but not the slots, and the two endpoints are in very
 * different states when it fires. The DESTINATION has not been written yet,
 * so an event-time snapshot of it is a faithful before-state and a next-tick
 * diff attributes the arrival exactly (the #268 shift-click pattern). The
 * SOURCE is untrustworthy at event time: Paper's hopper splits the stack
 * in place before firing, so the source slot reads as the single moved item
 * (observed live: a chest holding 5 read as x1 in every pull). A source-side
 * endpoint therefore reconstructs its before-state arithmetically at drain
 * time instead: current (true) contents, plus each of the tick's known moves
 * reversed - outbound items put back into the first same-material slot, else
 * the first empty slot (vanilla pulls take the first non-empty slot, so this
 * is the vanilla slot in the common case); inbound items taken back off the
 * first same-material slot. A dropper's random source slot can occasionally
 * be mis-attributed by this; the snapshot forward-replay check then flags
 * the window uncertain, which is the safe degraded mode.
 *
 * <p><b>Deliberately not rollbackable.</b> The records reuse the container
 * deposit/withdraw shapes (no codec/schema change), but the event names are
 * excluded from every rollback path ({@code EventCatalog.isRollbackable}): an
 * area rollback must not try to reverse thousands of hopper ticks (#226).
 *
 * <p><b>Cost.</b> Unlike the coalesced {@code transfer-out}/{@code
 * transfer-in} shape this replaces (one record per flow window), every
 * movement is recorded - that is the point. The per-event toggles and
 * per-event retention remain the relief valve for hopper-farm-heavy servers.
 */
@ApiStatus.Internal
public final class HopperTransferListener implements RecordingListener {

    private final Recorder recorder;
    private final RecordingSupport support;
    private final Executor serializer;
    // Main-thread, next-tick: where the per-slot diff runs, after the move
    // (and everything else this tick) has applied.
    private final Executor nextTick;
    // Per-event gating: the plugin gates only at listener-registration
    // granularity, so the independent toggles must be honoured here. Live,
    // thread-safe view of the enabled set.
    private final Set<String> enabledEvents;

    // MAIN THREAD ONLY (event handler + next-tick drain): endpoints touched
    // this tick, keyed by position so several same-tick events against one
    // container share a single snapshot and diff.
    private final Map<String, PendingEndpoint> pending = new LinkedHashMap<>();
    private boolean drainScheduled = false;

    public HopperTransferListener(Recorder recorder, RecordingSupport support, Executor serializer,
            Executor nextTick, Set<String> enabledEvents) {
        this.recorder = recorder;
        this.support = support;
        this.serializer = serializer;
        this.nextTick = nextTick;
        this.enabledEvents = enabledEvents;
    }

    @Override
    public Set<String> events() {
        return Set.of("transfer-deposit", "transfer-withdraw");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryMoveItem(InventoryMoveItemEvent event) {
        boolean logWithdraw = enabledEvents.contains("transfer-withdraw");
        boolean logDeposit = enabledEvents.contains("transfer-deposit");
        if (!logWithdraw && !logDeposit) {
            return;
        }
        ItemStack item = event.getItem();
        if (item == null || item.getType() == Material.AIR) {
            return;
        }
        // The mover mechanic (hopper / dropper / dispenser) for attribution.
        // Guarded because a mocked or unusual inventory can report a null type.
        InventoryType initiatorType = event.getInitiator().getType();
        String mover = initiatorType != null
                ? initiatorType.name().toLowerCase(Locale.ROOT)
                : "hopper";
        // Both endpoints register the move; the diff decides direction per
        // slot, and the per-direction toggles apply at emit time.
        ItemStack moved = item.clone();
        registerEndpoint(event.getSource(), moved, false, mover);
        registerEndpoint(event.getDestination(), moved, true, mover);
    }

    /** MAIN THREAD: note the move against the endpoint; a pure-destination
     *  endpoint also snapshots its (still unwritten) contents. No-op for
     *  inventories with no world position. */
    private void registerEndpoint(Inventory inventory, ItemStack moved, boolean intoThis,
            String mover) {
        Location location = inventory.getLocation();
        if (location == null) {
            return; // a virtual inventory with no world position
        }
        BlockLocation at = BlockLocations.fromLocation(location);
        String key = at.worldId() + ":" + at.x() + ":" + at.y() + ":" + at.z();
        PendingEndpoint endpoint = pending.get(key);
        if (endpoint == null) {
            endpoint = new PendingEndpoint(inventory, resolveTarget(inventory, at), mover);
            if (intoThis) {
                // Destination contents are pre-write here, so the snapshot is
                // a faithful before-state.
                endpoint.snapshot = cloneContents(inventory);
            }
            pending.put(key, endpoint);
        }
        if (!intoThis) {
            // Source contents are mid-split at event time (see class doc):
            // poison any snapshot and fall back to drain-time arithmetic.
            endpoint.snapshot = null;
        }
        endpoint.moves.add(new Move(moved, intoThis));
        if (!drainScheduled) {
            drainScheduled = true;
            nextTick.execute(this::drain);
        }
    }

    private static ItemStack[] cloneContents(Inventory inventory) {
        int size = inventory.getSize();
        ItemStack[] out = new ItemStack[size];
        for (int i = 0; i < size; i++) {
            ItemStack stack = inventory.getItem(i);
            out[i] = stack == null ? null : stack.clone();
        }
        return out;
    }

    /** MAIN THREAD, next tick: resolve every touched endpoint's before-state
     *  and emit one record per changed slot. */
    private void drain() {
        drainScheduled = false;
        List<PendingEndpoint> batch = new ArrayList<>(pending.values());
        pending.clear();
        boolean logWithdraw = enabledEvents.contains("transfer-withdraw");
        boolean logDeposit = enabledEvents.contains("transfer-deposit");
        Instant occurred = support.now();
        for (PendingEndpoint endpoint : batch) {
            ItemStack[] now = cloneContents(endpoint.inventory);
            ItemStack[] before = endpoint.snapshot != null
                    ? endpoint.snapshot
                    : reverseMoves(now, endpoint.moves);
            emitChangedSlots(endpoint, before, now, occurred, logWithdraw, logDeposit);
        }
    }

    /**
     * Reconstruct an endpoint's pre-tick contents from its true post-tick
     * contents by reversing this tick's known moves, newest first: an
     * outbound item goes back into the first slot holding the same material
     * (else the first empty slot), an inbound item comes back off the first
     * slot holding it.
     */
    private static ItemStack[] reverseMoves(ItemStack[] now, List<Move> moves) {
        ItemStack[] before = new ItemStack[now.length];
        for (int i = 0; i < now.length; i++) {
            before[i] = now[i] == null ? null : now[i].clone();
        }
        for (int m = moves.size() - 1; m >= 0; m--) {
            Move move = moves.get(m);
            int remaining = move.item.getAmount();
            if (move.intoThis) {
                // Undo an arrival: take the items back off matching slots.
                for (int i = 0; i < before.length && remaining > 0; i++) {
                    ItemStack stack = before[i];
                    if (stack == null || stack.getType() != move.item.getType()) {
                        continue;
                    }
                    int take = Math.min(stack.getAmount(), remaining);
                    remaining -= take;
                    if (stack.getAmount() - take <= 0) {
                        before[i] = null;
                    } else {
                        stack.setAmount(stack.getAmount() - take);
                    }
                }
            } else {
                // Undo a departure: put the items back, matching slots first.
                for (int i = 0; i < before.length && remaining > 0; i++) {
                    ItemStack stack = before[i];
                    if (stack == null || stack.getType() != move.item.getType()) {
                        continue;
                    }
                    int room = stack.getMaxStackSize() - stack.getAmount();
                    if (room <= 0) {
                        continue;
                    }
                    int put = Math.min(room, remaining);
                    stack.setAmount(stack.getAmount() + put);
                    remaining -= put;
                }
                for (int i = 0; i < before.length && remaining > 0; i++) {
                    if (before[i] != null) {
                        continue;
                    }
                    ItemStack placed = move.item.clone();
                    int put = Math.min(Math.max(1, placed.getMaxStackSize()), remaining);
                    placed.setAmount(put);
                    before[i] = placed;
                    remaining -= put;
                }
            }
        }
        return before;
    }

    private void emitChangedSlots(PendingEndpoint endpoint, ItemStack[] before, ItemStack[] now,
            Instant occurred, boolean logWithdraw, boolean logDeposit) {
        int slots = Math.min(before.length, now.length);
        for (int slot = 0; slot < slots; slot++) {
            ItemStack was = before[slot];
            ItemStack current = now[slot];
            boolean wasEmpty = was == null || was.getType() == Material.AIR;
            boolean nowEmpty = current == null || current.getType() == Material.AIR;
            if (wasEmpty && nowEmpty) {
                continue;
            }
            if (!wasEmpty && !nowEmpty && was.getType() != current.getType()) {
                // A swap to a different material is a player action (a
                // hopper merge never replaces a stack); not ours.
                continue;
            }
            Material material = nowEmpty ? was.getType() : current.getType();
            if (!endpoint.touched(material)) {
                continue; // foreign material: not this tick's transfer
            }
            int delta = (nowEmpty ? 0 : current.getAmount()) - (wasEmpty ? 0 : was.getAmount());
            if (delta == 0) {
                continue;
            }
            SlotTarget target = endpoint.target.slotTarget(slot);
            if (delta < 0 && logWithdraw) {
                emit(false, endpoint.mover, target, material.name(), -delta,
                        occurred, was, nowEmpty ? null : current);
            } else if (delta > 0 && logDeposit) {
                emit(true, endpoint.mover, target, material.name(), delta,
                        occurred, wasEmpty ? null : was, current);
            }
        }
    }

    /** MAIN THREAD: mint the id and context, then hand the heavy stack
     *  serialization and the record build to the off-main serializer. */
    private void emit(boolean deposit, String mover, SlotTarget target, String material,
            int amount, Instant occurred, @Nullable ItemStack before, @Nullable ItemStack after) {
        UUID id = support.newId();
        Origin origin = support.environmentOrigin("transfer:" + mover);
        Source source = support.environmentSource(mover);
        Instant expiresAt = support.expiresAt(occurred);
        String server = support.serverName();
        int slot = target.slot();
        BlockLocation location = target.location();
        String containerType = target.type();
        serializer.execute(() -> {
            StoredItem beforeItem = ItemSerialization.storedItem(slot, before);
            StoredItem afterItem = ItemSerialization.storedItem(slot, after);
            if (deposit) {
                recorder.record(new ContainerDepositRecord(
                        id, "transfer-deposit", occurred, expiresAt, origin, source,
                        location, server, material, containerType, slot, amount,
                        beforeItem, afterItem));
            } else {
                recorder.record(new ContainerWithdrawRecord(
                        id, "transfer-withdraw", occurred, expiresAt, origin, source,
                        location, server, material, containerType, slot, amount,
                        beforeItem, afterItem));
            }
        });
    }

    // ── endpoint targets ─────────────────────────────────────────────

    /**
     * Resolve where a slot's record should be pinned. Single-block containers
     * and entity holders (hopper minecarts and friends) pin every slot to one
     * (location, type); a double chest re-bases the combined 0-53 slot to the
     * owning half's block and its local 0-26 slot - the same mapping the
     * click listener performs, so the snapshot reconstructor's per-half
     * queries see transfer and click records in the same coordinate space.
     * (Shared-resolver unification with the container listeners is deferred
     * until #353 lands; this mirrors its mapping.)
     */
    private EndpointTarget resolveTarget(Inventory inventory, BlockLocation fallback) {
        InventoryHolder holder = null;
        try {
            holder = inventory.getHolder();
        } catch (RuntimeException ignored) {
            // A broken third-party inventory: fall through to the location.
        }
        if (holder instanceof DoubleChest doubleChest
                && doubleChest.getLeftSide() instanceof Chest left
                && doubleChest.getRightSide() instanceof Chest right) {
            BlockLocation leftAt = BlockLocations.fromLocation(left.getBlock().getLocation());
            String leftType = left.getBlock().getType().name();
            BlockLocation rightAt = BlockLocations.fromLocation(right.getBlock().getLocation());
            String rightType = right.getBlock().getType().name();
            int leftSize = left.getBlockInventory().getSize();
            return slot -> slot >= leftSize
                    ? new SlotTarget(rightAt, rightType, slot - leftSize)
                    : new SlotTarget(leftAt, leftType, slot);
        }
        if (holder instanceof Container blockContainer) {
            BlockLocation at = BlockLocations.fromLocation(blockContainer.getBlock().getLocation());
            String type = blockContainer.getBlock().getType().name();
            return slot -> new SlotTarget(at, type, slot);
        }
        if (holder instanceof Entity entity) {
            BlockLocation at = BlockLocations.fromLocation(entity.getLocation());
            String type = entity.getType().name();
            return slot -> new SlotTarget(at, type, slot);
        }
        InventoryType inventoryType = null;
        try {
            inventoryType = inventory.getType();
        } catch (RuntimeException ignored) {
        }
        String type = inventoryType != null ? inventoryType.name() : "CONTAINER";
        return slot -> new SlotTarget(fallback, type, slot);
    }

    private interface EndpointTarget {
        SlotTarget slotTarget(int slot);
    }

    private record SlotTarget(BlockLocation location, String type, int slot) {
    }

    /** One inventory touched this tick: which moves involved it (and in
     *  which direction), plus a faithful before-snapshot when it was only
     *  ever a destination. */
    private static final class PendingEndpoint {
        final Inventory inventory;
        final EndpointTarget target;
        final String mover;
        final List<Move> moves = new ArrayList<>(2);
        @Nullable
        ItemStack[] snapshot;

        PendingEndpoint(Inventory inventory, EndpointTarget target, String mover) {
            this.inventory = inventory;
            this.target = target;
            this.mover = mover;
        }

        boolean touched(Material material) {
            for (Move move : moves) {
                if (move.item.getType() == material) {
                    return true;
                }
            }
            return false;
        }
    }

    private record Move(ItemStack item, boolean intoThis) {
    }
}
