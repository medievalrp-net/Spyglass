package net.medievalrp.omniscience2.api.event;

import java.time.Instant;
import java.util.UUID;
import net.medievalrp.omniscience2.api.rollback.RollbackEffect;
import net.medievalrp.omniscience2.api.rollback.Rollbackable;
import net.medievalrp.omniscience2.api.util.BlockLocation;



public record BlockBreakRecord(
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

    public static BlockBreakRecord of(RecordContext ctx, String event, String target,
                                      BlockSnapshot originalBlock, BlockSnapshot newBlock) {
        return new BlockBreakRecord(
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
