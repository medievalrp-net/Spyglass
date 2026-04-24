package net.medievalrp.spyglass.api.event;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import net.medievalrp.spyglass.api.util.BlockLocation;



public record ChatRecord(
        UUID id,
        int schemaVersion,
        String event,
        Instant occurred,
        Instant expiresAt,
        Origin origin,
        Source source,
        BlockLocation location,
        String target,
        String message,
        List<UUID> recipients) implements EventRecord {

    public ChatRecord {
        recipients = List.copyOf(recipients);
    }

    public static ChatRecord of(RecordContext ctx, String target, String message, List<UUID> recipients) {
        return new ChatRecord(
                ctx.id(), ctx.schemaVersion(), "say", ctx.occurred(), ctx.expiresAt(),
                ctx.origin(), ctx.source(), ctx.location(),
                target, message, recipients);
    }
}
