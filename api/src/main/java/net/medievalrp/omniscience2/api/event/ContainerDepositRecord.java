package net.medievalrp.omniscience2.api.event;

import java.time.Instant;
import java.util.UUID;
import net.medievalrp.omniscience2.api.rollback.RollbackEffect;
import net.medievalrp.omniscience2.api.rollback.Rollbackable;
import net.medievalrp.omniscience2.api.util.BlockLocation;

public record ContainerDepositRecord(
        UUID id,
        int schemaVersion,
        String event,
        Instant occurred,
        Instant expiresAt,
        Origin origin,
        Source source,
        BlockLocation location,
        String target,
        String containerType,
        int slot,
        int amount,
        StoredItem beforeItem,
        StoredItem afterItem) implements EventRecord, Rollbackable {

    @Override
    public RollbackEffect rollbackEffect() {
        // Undo the deposit: restore the slot to its pre-deposit state.
        return new RollbackEffect.ContainerSlotWrite(location, slot, afterItem, beforeItem);
    }

    @Override
    public RollbackEffect restoreEffect() {
        // Re-apply the deposit: write the post-deposit state.
        return new RollbackEffect.ContainerSlotWrite(location, slot, beforeItem, afterItem);
    }
}
