package net.medievalrp.spyglass.plugin.salvage;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import net.medievalrp.spyglass.api.event.StoredItem;
import org.bson.codecs.pojo.annotations.BsonProperty;

/**
 * One container inventory captured because a rollback was about to destroy it
 * (overwrite the block, or clear/replace its contents). Persisted to the
 * {@link SalvageStore} and surfaced through {@code /sg inventory} so an operator
 * can recover the items rather than losing them to a force-overwrite rollback.
 *
 * <p>{@code items} are the live contents at capture time, serialized with the
 * same {@code StoredItem} blob used everywhere else (base64
 * {@code ItemStack.serializeAsBytes()}), so they reconstruct faithfully.
 */
public record SalvageSnapshot(
        @BsonProperty("_id") UUID id,
        UUID rollbackOpId,
        UUID worldId,
        String worldName,
        int x,
        int y,
        int z,
        String containerType,
        String operatorName,
        Instant capturedAt,
        List<StoredItem> items) {

    public SalvageSnapshot {
        items = items == null ? List.of() : List.copyOf(items);
    }

    /** A copy with a different item list — used after an operator extracts. */
    public SalvageSnapshot withItems(List<StoredItem> remaining) {
        return new SalvageSnapshot(id, rollbackOpId, worldId, worldName,
                x, y, z, containerType, operatorName, capturedAt, remaining);
    }
}
