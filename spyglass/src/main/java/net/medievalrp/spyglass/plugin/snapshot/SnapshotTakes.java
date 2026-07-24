package net.medievalrp.spyglass.plugin.snapshot;

import java.util.List;
import net.medievalrp.spyglass.plugin.util.ItemSerialization;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * The extract path for {@code /sg snapshot}, shared by the InvUI GUI and
 * the text-fallback take command (#341) - the same split
 * {@code SalvageWithdrawals} draws between its two surfaces.
 *
 * <p>A take is a COPY, not a withdrawal: the session's slots never change
 * ({@link SnapshotSession}'s javadoc - "sessions never write back, takes
 * clone out of them"), so nothing here mutates a session or a store row.
 * The audit record ({@link SnapshotTakeLogger}) is the only control that
 * keeps repeated takes honest - by design, the same slot can be taken
 * again a minute later, and that is fine because every take is logged.
 *
 * <p>Unlike {@code SalvageWithdrawals}, a stack that cannot fit whole is
 * refused outright: no partial placement, no overflow dropped on the
 * ground. That is the simplest dupe-story available here - a feature
 * whose entire point is "you can take this again" cannot also reason
 * about which fraction of a stack a prior partial take already covered.
 */
@ApiStatus.Internal
public final class SnapshotTakes {

    /** Gates every take, in both the GUI and the text fallback. */
    public static final String PERMISSION = "spyglass.snapshot.take";

    public enum Result {
        /** The item was cloned into the taker's inventory and the take was logged. */
        TAKEN,
        /** The stack would not fit whole; nothing was placed. */
        INVENTORY_FULL,
        /** The taker lacks {@link #PERMISSION}. */
        NO_PERMISSION,
        /** No occupied slot at that index in the session (already gone, bad index). */
        SLOT_EMPTY
    }

    /** Null in tests that don't care about the audit trail (matches
     *  {@code SalvageWithdrawals}'s own null-logger test idiom). */
    @Nullable
    private final SnapshotTakeLogger logger;

    public SnapshotTakes(@Nullable SnapshotTakeLogger logger) {
        this.logger = logger;
    }

    /**
     * Take a copy of the item at {@code slot} out of {@code session} into
     * {@code taker}'s inventory. Must run on the main thread - it reads
     * and writes the taker's live {@link PlayerInventory}.
     */
    public Result take(Player taker, SnapshotSession session, int slot) {
        if (!taker.hasPermission(PERMISSION)) {
            return Result.NO_PERMISSION;
        }
        SnapshotSlot found = findSlot(session.slots(), slot);
        if (found == null) {
            return Result.SLOT_EMPTY;
        }
        ItemStack stack = decode(session, found);
        if (stack == null || stack.getType() == Material.AIR) {
            return Result.SLOT_EMPTY;
        }
        PlayerInventory inventory = taker.getInventory();
        if (!fits(inventory.getStorageContents(), stack)) {
            return Result.INVENTORY_FULL;
        }
        inventory.addItem(stack.clone());
        if (logger != null) {
            logger.log(taker, session, stack, slot);
        }
        return Result.TAKEN;
    }

    /**
     * Decode the slot's stored blob into a live stack, applying the count
     * semantics that mode uses. Player-mode slots carry the real amount in
     * {@link SnapshotSlot#count()} - the interned blob is normalized to 1
     * ({@code PlayerSnapshotService}'s dirty-check trick) - so the decoded
     * stack's amount must be overwritten. Container-mode slots leave the
     * real amount inside the blob itself
     * ({@code SnapshotReconstructor#COUNT_IN_BLOB}) and must NOT have
     * {@code setAmount} called on them, or the count silently becomes 0.
     */
    private static @Nullable ItemStack decode(SnapshotSession session, SnapshotSlot slot) {
        ItemStack stack = ItemSerialization.decode(slot.item().data());
        if (stack == null) {
            return null;
        }
        if (session.kind() == SnapshotSession.Kind.PLAYER) {
            stack.setAmount(slot.count());
        }
        return stack;
    }

    private static @Nullable SnapshotSlot findSlot(List<SnapshotSlot> slots, int slot) {
        for (SnapshotSlot candidate : slots) {
            if (candidate.slot() == slot) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Whether {@code candidate} would fit entirely into
     * {@code storageContents} - the 36 main-inventory slots
     * {@link PlayerInventory#addItem} actually fills, never armor or off
     * hand - without any partial placement.
     *
     * <p>Bukkit has no side-effect-free "would this fit" call, and a
     * scratch-inventory dry run would need a live CraftBukkit
     * implementation this module cannot spin up in a unit test. This
     * mirrors {@code Inventory#addItem}'s documented algorithm as a pure
     * simulation instead: first fill existing similar, non-full stacks,
     * then empty slots. The one simplification against the real
     * implementation is the per-slot cap - this uses only
     * {@code ItemStack#getMaxStackSize()}, not
     * {@code min(item cap, inventory cap)} - because a player inventory's
     * own cap is 64 by default and effectively never lowered.
     */
    static boolean fits(ItemStack[] storageContents, ItemStack candidate) {
        int remaining = candidate.getAmount();
        int maxStack = candidate.getMaxStackSize();
        for (ItemStack existing : storageContents) {
            if (remaining <= 0) {
                return true;
            }
            if (isEmpty(existing) || !existing.isSimilar(candidate)) {
                continue;
            }
            int space = existing.getMaxStackSize() - existing.getAmount();
            if (space > 0) {
                remaining -= space;
            }
        }
        for (ItemStack existing : storageContents) {
            if (remaining <= 0) {
                return true;
            }
            if (isEmpty(existing)) {
                remaining -= maxStack;
            }
        }
        return remaining <= 0;
    }

    private static boolean isEmpty(@Nullable ItemStack stack) {
        return stack == null || stack.getType() == Material.AIR;
    }
}
