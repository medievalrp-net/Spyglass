package net.medievalrp.spyglass.api.event;

import java.time.Instant;
import java.util.UUID;
import net.medievalrp.spyglass.api.util.BlockLocation;

public record ItemDropRecord(
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

    public static ItemDropRecord of(RecordContext ctx, String target, int amount, StoredItem item) {
        return of(ctx, "drop", target, amount, item);
    }

    /**
     * Same shape with an explicit event name, for events that reuse the drop
     * record without being a world "drop" - e.g. the source side of an
     * automated hopper/dropper transfer ("transfer-out", #226). Keeps
     * EventCatalog's name to record-class mapping honest with no codec change.
     */
    public static ItemDropRecord of(RecordContext ctx, String event, String target, int amount,
            StoredItem item) {
        return new ItemDropRecord(
                ctx.id(), event, ctx.occurred(), ctx.expiresAt(),
                ctx.origin(), ctx.source(), ctx.location(), ctx.server(), target, amount, item);
    }
}
