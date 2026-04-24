package net.medievalrp.omniscience2.api.event;

import java.time.Instant;
import java.util.UUID;
import net.medievalrp.omniscience2.api.util.BlockLocation;



public record JoinRecord(
        UUID id,
        int schemaVersion,
        String event,
        Instant occurred,
        Instant expiresAt,
        Origin origin,
        Source source,
        BlockLocation location,
        String target,
        String address) implements EventRecord {

    public static JoinRecord of(RecordContext ctx, String target, String address) {
        return new JoinRecord(
                ctx.id(), ctx.schemaVersion(), "join", ctx.occurred(), ctx.expiresAt(),
                ctx.origin(), ctx.source(), ctx.location(),
                target, address);
    }
}
