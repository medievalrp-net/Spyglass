package net.medievalrp.spyglass.api.event;

import java.time.Instant;
import java.util.UUID;
import net.medievalrp.spyglass.api.util.BlockLocation;



public record QuitRecord(
        UUID id,
        int schemaVersion,
        String event,
        Instant occurred,
        Instant expiresAt,
        Origin origin,
        Source source,
        BlockLocation location,
        String target) implements EventRecord {

    public static QuitRecord of(RecordContext ctx, String target) {
        return new QuitRecord(
                ctx.id(), ctx.schemaVersion(), "quit", ctx.occurred(), ctx.expiresAt(),
                ctx.origin(), ctx.source(), ctx.location(), target);
    }
}
