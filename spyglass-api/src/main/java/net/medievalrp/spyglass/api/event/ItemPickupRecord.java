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
        return of(ctx, "pickup", target, amount, item);
    }

    /**
     * Same shape with an explicit event name, for events that reuse the pickup
     * record without being a world "pickup" - e.g. the destination side of an
     * automated hopper/dropper transfer ("transfer-in", #226). Keeps
     * EventCatalog's name to record-class mapping honest with no codec change.
     */
    public static ItemPickupRecord of(RecordContext ctx, String event, String target, int amount,
            StoredItem item) {
        return new ItemPickupRecord(
                ctx.id(), event, ctx.occurred(), ctx.expiresAt(),
                ctx.origin(), ctx.source(), ctx.location(), ctx.server(), target, amount, item);
    }
}
