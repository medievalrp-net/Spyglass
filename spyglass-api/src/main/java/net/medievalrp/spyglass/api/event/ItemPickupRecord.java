package net.medievalrp.spyglass.api.event;

import java.time.Instant;
import java.util.UUID;
import net.medievalrp.spyglass.api.util.BlockLocation;

public record ItemPickupRecord(
        UUID id,
        String event,
        Instant occurred,
        Instant expiresAt,
        Origin origin,
        Source source,
        BlockLocation location,
        String server,
        String target,
        int amount,
        StoredItem item) implements EventRecord {

    public static ItemPickupRecord of(RecordContext ctx, String target, int amount, StoredItem item) {
        return new ItemPickupRecord(
                ctx.id(), "pickup", ctx.occurred(), ctx.expiresAt(),
                ctx.origin(), ctx.source(), ctx.location(), ctx.server(), target, amount, item);
    }
}
