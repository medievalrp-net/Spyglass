package net.medievalrp.spyglass.plugin.util;

import org.bukkit.Material;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Translates Bukkit {@link InventoryAction} values into the deposit/withdraw
 * direction + amount that the container extractors need. Shared between
 * {@code ContainerTransactionExtractor} and {@code ShulkerTransactionExtractor}
 * (and any other per-holder inventory extractor added later).
 */
@ApiStatus.Internal
public final class InventoryActions {

    public enum Direction {
        DEPOSIT,
        WITHDRAW
    }

    private InventoryActions() {
    }

    @Nullable
    public static Direction directionOf(InventoryAction action) {
        return switch (action) {
            case PLACE_ALL, PLACE_ONE, PLACE_SOME -> Direction.DEPOSIT;
            case PICKUP_ALL, PICKUP_HALF, PICKUP_ONE, PICKUP_SOME -> Direction.WITHDRAW;
            default -> null;
        };
    }

    public static int amountOf(InventoryAction action, @Nullable ItemStack slotItem, @Nullable ItemStack cursor) {
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
}
