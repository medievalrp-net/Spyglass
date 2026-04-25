package net.medievalrp.omniscience2.api.event;

import java.time.Instant;
import java.util.UUID;
import net.medievalrp.omniscience2.api.rollback.RollbackEffect;
import net.medievalrp.omniscience2.api.rollback.Rollbackable;
import net.medievalrp.omniscience2.api.util.BlockLocation;

public record ContainerWithdrawRecord(
        UUID id,
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

    public static ContainerWithdrawRecord of(RecordContext ctx, String event, String target,
                                             String containerType, int slot, int amount,
                                             StoredItem beforeItem, StoredItem afterItem) {
        return new ContainerWithdrawRecord(
                ctx.id(), event, ctx.occurred(), ctx.expiresAt(),
                ctx.origin(), ctx.source(), ctx.location(),
                target, containerType, slot, amount, beforeItem, afterItem);
    }

    @Override
    public RollbackEffect rollbackEffect() {
        // Undo the withdraw: put the item back in the slot.
        return new RollbackEffect.ContainerSlotWrite(location, slot, afterItem, beforeItem);
    }

    @Override
    public RollbackEffect restoreEffect() {
        // Re-apply the withdraw: take the item out again.
        return new RollbackEffect.ContainerSlotWrite(location, slot, beforeItem, afterItem);
    }
}
