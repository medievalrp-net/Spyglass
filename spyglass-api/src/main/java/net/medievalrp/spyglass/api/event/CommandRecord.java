package net.medievalrp.spyglass.api.event;

import java.time.Instant;
import java.util.UUID;
import net.medievalrp.spyglass.api.util.BlockLocation;



public record CommandRecord(
        UUID id,
        String event,
        Instant occurred,
        Instant expiresAt,
        Origin origin,
        Source source,
        BlockLocation location,
        String server,
        String target,
        String commandLine) implements EventRecord {

    public static CommandRecord of(RecordContext ctx, String target, String commandLine) {
        return new CommandRecord(
                ctx.id(), "command", ctx.occurred(), ctx.expiresAt(),
                ctx.origin(), ctx.source(), ctx.location(), ctx.server(),
                target, commandLine);
    }
}
