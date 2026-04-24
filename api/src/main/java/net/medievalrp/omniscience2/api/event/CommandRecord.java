package net.medievalrp.omniscience2.api.event;

import java.time.Instant;
import java.util.UUID;
import net.medievalrp.omniscience2.api.util.BlockLocation;



public record CommandRecord(
        UUID id,
        int schemaVersion,
        String event,
        Instant occurred,
        Instant expiresAt,
        Origin origin,
        Source source,
        BlockLocation location,
        String target,
        String commandLine) implements EventRecord {

    public static CommandRecord of(RecordContext ctx, String target, String commandLine) {
        return new CommandRecord(
                ctx.id(), ctx.schemaVersion(), "command", ctx.occurred(), ctx.expiresAt(),
                ctx.origin(), ctx.source(), ctx.location(),
                target, commandLine);
    }
}
