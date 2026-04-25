package net.medievalrp.omniscience2.api.event;

import java.time.Instant;
import java.util.UUID;
import net.medievalrp.omniscience2.api.util.BlockLocation;

public record EntityMountRecord(
        UUID id,
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
                ctx.id(), event, ctx.occurred(), ctx.expiresAt(),
                ctx.origin(), ctx.source(), ctx.location(),
                target, mountType, mountId, dismount);
    }
}
