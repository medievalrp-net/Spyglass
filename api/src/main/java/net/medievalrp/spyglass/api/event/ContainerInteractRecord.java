package net.medievalrp.spyglass.api.event;

import java.time.Instant;
import java.util.UUID;
import net.medievalrp.spyglass.api.util.BlockLocation;

/**
 * Lightweight record for container interactions that don't change inventory
 * state (open/close). Carries only the context fields plus the container
 * material name as {@code target}.
 */
public record ContainerInteractRecord(
        UUID id,
        int schemaVersion,
        String event,
        Instant occurred,
        Instant expiresAt,
        Origin origin,
        Source source,
        BlockLocation location,
        String target) implements EventRecord {

    public static ContainerInteractRecord of(RecordContext ctx, String event, String target) {
        return new ContainerInteractRecord(
                ctx.id(), ctx.schemaVersion(), event, ctx.occurred(), ctx.expiresAt(),
                ctx.origin(), ctx.source(), ctx.location(), target);
    }
}
