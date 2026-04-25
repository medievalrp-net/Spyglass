package net.medievalrp.omniscience2.api.event;

import java.time.Instant;
import java.util.UUID;
import net.medievalrp.omniscience2.api.util.BlockLocation;

public record ItemDropRecord(
        UUID id,
        String event,
        Instant occurred,
        Instant expiresAt,
        Origin origin,
        Source source,
        BlockLocation location,
        String target,
        int amount,
        StoredItem item) implements EventRecord {

    public static ItemDropRecord of(RecordContext ctx, String target, int amount, StoredItem item) {
        return new ItemDropRecord(
                ctx.id(), "drop", ctx.occurred(), ctx.expiresAt(),
                ctx.origin(), ctx.source(), ctx.location(), target, amount, item);
    }
}
