package net.medievalrp.spyglass.api.event;

import java.time.Instant;
import java.util.UUID;
import net.medievalrp.spyglass.api.util.BlockLocation;

public record TeleportRecord(
        UUID id,
        String event,
        Instant occurred,
        Instant expiresAt,
        Origin origin,
        Source source,
        BlockLocation location,
        String target,
        BlockLocation from,
        BlockLocation to,
        String cause) implements EventRecord {

    public static TeleportRecord of(RecordContext ctx, String target,
                                    BlockLocation from, BlockLocation to, String cause) {
        return new TeleportRecord(
                ctx.id(), "teleport", ctx.occurred(), ctx.expiresAt(),
                ctx.origin(), ctx.source(), ctx.location(),
                target, from, to, cause);
    }
}
