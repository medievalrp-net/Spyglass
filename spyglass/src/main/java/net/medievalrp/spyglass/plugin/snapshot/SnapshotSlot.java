package net.medievalrp.spyglass.plugin.snapshot;

import net.medievalrp.spyglass.api.event.StoredItem;

/**
 * One occupied slot inside a {@link PlayerSnapshot}. The {@code item} blob is
 * serialized with its amount normalized to 1 and the real amount kept here, so
 * the interned payload for "64 cobblestone" and "12 cobblestone" is the same
 * row. Reconstruction is {@code decode(item.data()).setAmount(count)}.
 *
 * <p>Slot indices follow {@code PlayerInventory.getContents()}: 0-35 main,
 * 36-39 armor, 40 offhand.
 */
public record SnapshotSlot(int slot, int count, StoredItem item) {
}
