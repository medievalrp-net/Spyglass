package net.medievalrp.spyglass.api.event;

import java.time.Instant;
import java.util.UUID;
import net.medievalrp.spyglass.api.rollback.RollbackEffect;
import net.medievalrp.spyglass.api.rollback.Rollbackable;
import net.medievalrp.spyglass.api.util.BlockLocation;

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

    public static ContainerDepositRecord of(RecordContext ctx, String event, String target,
                                            String containerType, int slot, int amount,
                                            StoredItem beforeItem, StoredItem afterItem) {
        return new ContainerDepositRecord(
                ctx.id(), ctx.schemaVersion(), event, ctx.occurred(), ctx.expiresAt(),
                ctx.origin(), ctx.source(), ctx.location(),
                target, containerType, slot, amount, beforeItem, afterItem);
    }

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
