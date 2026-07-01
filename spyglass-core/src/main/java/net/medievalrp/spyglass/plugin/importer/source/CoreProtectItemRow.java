package net.medievalrp.spyglass.plugin.importer.source;

import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Denormalized {@code co_item} row. CoreProtect uses this table for any
 * non-container item movement; {@code action} is one of:
 *
 * <pre>
 *  0 = ITEM_REMOVE        (taken from a player ender-context)
 *  1 = ITEM_ADD           (added to a player ender-context)
 *  2 = ITEM_DROP          (dropped to ground)
 *  3 = ITEM_PICKUP        (picked up off ground)
 *  4 = ITEM_REMOVE_ENDER  (taken from ender chest)
 *  5 = ITEM_ADD_ENDER     (added to ender chest)
 *  6 = ITEM_THROW
 *  7 = ITEM_SHOOT
 *  8 = ITEM_BREAK         (durability ran out)
 *  9 = ITEM_DESTROY       (consumed by lava / cactus / etc.)
 * 10 = ITEM_CREATE
 * 11 = ITEM_SELL
 * 12 = ITEM_BUY
 * </pre>
 *
 * <p>Spyglass currently models {@code drop} and {@code pickup} as
 * first-class events; the rest are mapped opportunistically or skipped.
 */
public record CoreProtectItemRow(
        long rowid,
        long timeEpochSeconds,
        String worldName,
        int x, int y, int z,
        String materialName,
        int amount,
        int action,
        boolean rolledBack,
        @Nullable byte[] metadata,
        String playerName,
        @Nullable UUID playerUuid) {
}
