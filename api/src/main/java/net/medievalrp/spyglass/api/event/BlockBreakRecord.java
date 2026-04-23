package net.medievalrp.spyglass.api.event;

import java.time.Instant;
import java.util.UUID;
import net.medievalrp.spyglass.api.rollback.RollbackEffect;
import net.medievalrp.spyglass.api.rollback.Rollbackable;
import net.medievalrp.spyglass.api.util.BlockLocation;



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

    @Override
    public RollbackEffect rollbackEffect() {
        return new RollbackEffect.BlockReplace(location, newBlock, originalBlock);
    }

    @Override
    public RollbackEffect restoreEffect() {
        return new RollbackEffect.BlockReplace(location, originalBlock, newBlock);
    }
}
