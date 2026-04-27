package net.medievalrp.spyglass.plugin.util;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.Container;
import org.bukkit.block.DoubleChest;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;

/**
 * Snapshot of the non-empty stacks in a container's inventory at the moment
 * its block is recorded as broken. Used by the explosion path to mirror v1's
 * behavior of emitting a {@code drop} record per stack when a chest is blown
 * up — the host break already carries a full {@code containerItems} snapshot
 * for rollback, but operators want the per-item drops in search output too.
 *
 * <p>For double-chests, only the half whose block matches the host is read,
 * so a creeper destroying the right half doesn't double-count items that
 * properly belong to the still-standing left half.
 */
@ApiStatus.Internal
public final class ContainerContents {

    private ContainerContents() {
    }

    public static List<ItemStack> stacksOf(BlockState state) {
        if (!(state instanceof Container container)) {
            return List.of();
        }
        Inventory inventory = container.getInventory();
        if (inventory.getHolder() instanceof DoubleChest doubleChest) {
            inventory = halfMatching(doubleChest, state).getBlockInventory();
        }
        List<ItemStack> out = new ArrayList<>();
        for (ItemStack stack : inventory) {
            if (stack == null || stack.getAmount() <= 0) {
                continue;
            }
            out.add(stack);
        }
        return out;
    }

    private static Chest halfMatching(DoubleChest doubleChest, BlockState state) {
        Chest left = (Chest) doubleChest.getLeftSide();
        Chest right = (Chest) doubleChest.getRightSide();
        if (left != null && sameBlock(left.getBlock().getState(), state)) {
            return left;
        }
        if (right != null && sameBlock(right.getBlock().getState(), state)) {
            return right;
        }
        return left != null ? left : right;
    }

    private static boolean sameBlock(BlockState a, BlockState b) {
        return a.getX() == b.getX() && a.getY() == b.getY() && a.getZ() == b.getZ();
    }
}
