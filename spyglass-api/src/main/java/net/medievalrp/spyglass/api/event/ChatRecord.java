package net.medievalrp.spyglass.api.event;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.medievalrp.spyglass.api.util.BlockLocation;



public record ChatRecord(
        UUID id,
        String event,
        Instant occurred,
        Instant expiresAt,
        Origin origin,
        Source source,
        BlockLocation location,
        String server,
        String target,
        String message,
        List<UUID> recipients,
        Map<String, String> extensions) implements EventRecord {

    public ChatRecord {
        recipients = List.copyOf(recipients);
        extensions = extensions == null ? Map.of() : Map.copyOf(extensions);
    }

    public static ChatRecord of(RecordContext ctx, String target, String message, List<UUID> recipients) {
        return new ChatRecord(
                ctx.id(), "say", ctx.occurred(), ctx.expiresAt(),
                ctx.origin(), ctx.source(), ctx.location(), ctx.server(),
                target, message, recipients, ctx.extensions());
    }
}
