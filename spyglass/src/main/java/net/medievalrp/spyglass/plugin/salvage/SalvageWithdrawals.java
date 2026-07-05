package net.medievalrp.spyglass.plugin.salvage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.logging.Logger;
import net.medievalrp.spyglass.api.event.StoredItem;
import net.medievalrp.spyglass.plugin.util.ItemSerialization;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * The extract path for salvage, shared by the InvUI GUI ({@link InvUiSalvageView})
 * and the command path ({@code SalvageService}). Taking items out of a captured
 * container is the correctness-critical part (dupe prevention), so it lives in
 * one place with its own tests rather than being duplicated per surface.
 *
 * <p>Every public method runs on the Bukkit main thread; the store write is
 * dispatched async. A slot is marked in-flight before its write is dispatched
 * and cleared only when that write settles, so a second take on the same slot
 * during the write window is refused. That, plus callers filtering in-flight
 * slots out of any DB re-read, closes the take-then-reopen dupe exploit across
 * both the GUI and the command, and across two staff acting on one snapshot
 * ({@link InFlightTracker}).
 */
public final class SalvageWithdrawals {

    /** Outcome of a single-slot {@link #withdraw}, telling the GUI how to refresh. */
    enum Status {
        /** Item taken; the container still has items ({@code updated} is the new snapshot). */
        TAKEN,
        /** Item taken and the container is now empty; the snapshot was deleted. */
        EMPTIED,
        /** Refused - the slot's previous write is still in-flight (dupe guard). */
        REFUSED,
        /** The player's inventory was full; nothing changed. */
        FULL,
        /** Nothing to take (out of range, or the blob decoded to air/null). */
        SKIPPED
    }

    record Outcome(Status status, SalvageSnapshot updated) {
    }

    /** Summary of a whole-snapshot {@link #withdrawAll} (the command path). */
    public record BulkResult(int stacksTaken, int itemsTaken, boolean emptied, boolean inventoryFull) {
    }

    private final SalvageStore store;
    private final Executor storeExecutor;
    private final SalvageWithdrawLogger withdrawLogger;
    private final InFlightTracker inFlight = new InFlightTracker();
    private final Logger logger;

    public SalvageWithdrawals(SalvageStore store, Executor storeExecutor,
                              SalvageWithdrawLogger withdrawLogger, Logger logger) {
        this.store = store;
        this.storeExecutor = storeExecutor;
        this.withdrawLogger = withdrawLogger;
        this.logger = logger;
    }

    InFlightTracker inFlight() {
        return inFlight;
    }

    /**
     * Take the single item at {@code index} of {@code snap} into the player's
     * inventory (GUI path). Must run on the main thread.
     */
    Outcome withdraw(Player player, SalvageSnapshot snap, int index) {
        List<StoredItem> items = snap.items();
        if (index < 0 || index >= items.size()) {
            return new Outcome(Status.SKIPPED, snap);
        }
        StoredItem storedItem = items.get(index);
        int slot = storedItem.slot();
        if (!inFlight.markInFlight(snap.id(), slot)) {
            logger.fine("Spyglass salvage take refused: slot " + slot
                    + " of snapshot " + snap.id() + " is already in-flight");
            return new Outcome(Status.REFUSED, snap);
        }
        Taken taken = takeMarkedSlot(player, snap, storedItem);
        if (taken == null) {              // decoded to air/null - nothing to take
            inFlight.clear(snap.id(), slot);
            return new Outcome(Status.SKIPPED, snap);
        }
        if (taken.amount() == 0) {        // inventory full
            inFlight.clear(snap.id(), slot);
            return new Outcome(Status.FULL, snap);
        }
        List<StoredItem> updated = new ArrayList<>(items);
        if (taken.leftover() == null) {
            updated.remove(index);
        } else {
            updated.set(index, taken.leftover());
        }
        persist(snap.id(), updated, List.of(slot));
        return updated.isEmpty()
                ? new Outcome(Status.EMPTIED, null)
                : new Outcome(Status.TAKEN, snap.withItems(updated));
    }

    /**
     * Take every remaining item of {@code snap} into the player's inventory
     * (command path). Must run on the main thread; issues one store write.
     */
    public BulkResult withdrawAll(Player player, SalvageSnapshot snap) {
        List<StoredItem> leftovers = new ArrayList<>();
        List<Integer> marked = new ArrayList<>();
        int stacks = 0;
        int count = 0;
        boolean full = false;
        for (StoredItem storedItem : snap.items()) {
            int slot = storedItem.slot();
            if (!inFlight.markInFlight(snap.id(), slot)) {
                // Another take on this slot is in-flight; keep it and skip.
                leftovers.add(storedItem);
                continue;
            }
            marked.add(slot);
            Taken taken = takeMarkedSlot(player, snap, storedItem);
            if (taken == null) {
                continue;                 // air/null - drop the slot
            }
            if (taken.amount() > 0) {
                stacks++;
                count += taken.amount();
            }
            if (taken.leftover() != null) {
                leftovers.add(taken.leftover());
                if (taken.amount() == 0) {
                    full = true;          // could not place any of this stack
                }
            }
        }
        boolean emptied = leftovers.isEmpty();
        persist(snap.id(), leftovers, marked);
        return new BulkResult(stacks, count, emptied, full);
    }

    // ---- shared per-slot core ------------------------------------------

    private record Taken(int amount, StoredItem leftover) {
    }

    /**
     * Decode one already-marked slot, place it in the player's inventory, and log
     * the take. Returns {@code null} if the blob is air/null (caller drops the
     * slot). Otherwise {@code amount} is how many were placed (0 = inventory full)
     * and {@code leftover} is the remaining {@link StoredItem} (null if all placed).
     * The caller owns the in-flight mark and the store write.
     */
    private Taken takeMarkedSlot(Player player, SalvageSnapshot snap, StoredItem storedItem) {
        ItemStack stack = ItemSerialization.decode(storedItem.data());
        if (stack == null || stack.getType() == Material.AIR) {
            return null;
        }
        Map<Integer, ItemStack> overflow = player.getInventory().addItem(stack.clone());
        ItemStack remaining = overflow.isEmpty() ? null : overflow.values().iterator().next();
        int placed = stack.getAmount() - (remaining == null ? 0 : remaining.getAmount());
        if (placed > 0 && withdrawLogger != null) {
            ItemStack takenStack = stack.clone();
            takenStack.setAmount(placed);
            try {
                withdrawLogger.log(player, snap, takenStack, placed);
            } catch (RuntimeException ex) {
                logger.warning("Spyglass salvage withdraw log failed: " + ex.getMessage());
            }
        }
        StoredItem leftover = remaining == null
                ? null
                : ItemSerialization.storedItem(storedItem.slot(), remaining);
        return new Taken(placed, leftover);
    }

    /**
     * Dispatch the store write for the new item list, then clear the in-flight
     * marks this call placed. Clears only {@code markedSlots} (not the whole
     * snapshot) so a concurrent take on another slot keeps its guard.
     */
    private void persist(UUID id, List<StoredItem> remaining, List<Integer> markedSlots) {
        boolean emptied = remaining.isEmpty();
        List<StoredItem> snapshot = List.copyOf(remaining);
        storeExecutor.execute(() -> {
            try {
                if (emptied) {
                    store.delete(id);
                } else {
                    store.replaceItems(id, snapshot);
                }
            } catch (RuntimeException ex) {
                logger.warning("Spyglass salvage persist failed: " + ex.getMessage());
                // Fall through and clear the marks so slots do not lock forever.
            } finally {
                for (int slot : markedSlots) {
                    inFlight.clear(id, slot);
                }
            }
        });
    }
}
