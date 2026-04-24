package net.medievalrp.spyglass.api.event;

import java.time.Instant;
import java.util.UUID;
import net.medievalrp.spyglass.api.util.BlockLocation;

public record EntityMountRecord(
        UUID id,
        int schemaVersion,
        String event,
        Instant occurred,
        Instant expiresAt,
        Origin origin,
        Source source,
        BlockLocation location,
        String target,
        String mountType,
        UUID mountId,
        boolean dismount) implements EventRecord {

    public static EntityMountRecord of(RecordContext ctx, String event, String target,
                                       String mountType, UUID mountId, boolean dismount) {
        return new EntityMountRecord(
                ctx.id(), ctx.schemaVersion(), event, ctx.occurred(), ctx.expiresAt(),
                ctx.origin(), ctx.source(), ctx.location(),
                target, mountType, mountId, dismount);
    }
}
