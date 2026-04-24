package net.medievalrp.spyglass.api.event;

import java.time.Instant;
import java.util.UUID;
import net.medievalrp.spyglass.api.util.BlockLocation;

/**
 * Player interaction with an interactive block that doesn't change blocks or
 * inventory - lever toggle, button press, note block tune, sculk sensor
 * trigger, etc. Carries only the context fields plus the block material as
 * {@code target}.
 */
public record BlockUseRecord(
        UUID id,
        int schemaVersion,
        String event,
        Instant occurred,
        Instant expiresAt,
        Origin origin,
        Source source,
        BlockLocation location,
        String target) implements EventRecord {

    public static BlockUseRecord of(RecordContext ctx, String target) {
        return new BlockUseRecord(
                ctx.id(), ctx.schemaVersion(), "use", ctx.occurred(), ctx.expiresAt(),
                ctx.origin(), ctx.source(), ctx.location(), target);
    }
}
