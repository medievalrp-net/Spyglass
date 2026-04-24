package net.medievalrp.spyglass.api.event;

import java.time.Instant;
import java.util.UUID;
import net.medievalrp.spyglass.api.rollback.RollbackEffect;
import net.medievalrp.spyglass.api.rollback.Rollbackable;
import net.medievalrp.spyglass.api.util.BlockLocation;



public record BlockPlaceRecord(
        UUID id,
        int schemaVersion,
        String event,
        Instant occurred,
        Instant expiresAt,
        Origin origin,
        Source source,
        BlockLocation location,
        String target,
        BlockSnapshot originalBlock,
        BlockSnapshot newBlock) implements EventRecord, Rollbackable {

    public static BlockPlaceRecord of(RecordContext ctx, String event, String target,
                                      BlockSnapshot originalBlock, BlockSnapshot newBlock) {
        return new BlockPlaceRecord(
                ctx.id(), ctx.schemaVersion(), event, ctx.occurred(), ctx.expiresAt(),
                ctx.origin(), ctx.source(), ctx.location(),
                target, originalBlock, newBlock);
    }

    @Override
    public RollbackEffect rollbackEffect() {
        return new RollbackEffect.BlockReplace(location, newBlock, originalBlock);
    }

    @Override
    public RollbackEffect restoreEffect() {
        return new RollbackEffect.BlockReplace(location, originalBlock, newBlock);
    }
}
