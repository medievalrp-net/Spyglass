package net.medievalrp.spyglass.api.event;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import net.medievalrp.spyglass.api.util.BlockLocation;

/**
 * {@code extensions} is the opt-in metadata channel from {@link RecordContext}
 * (see {@link EventRecord#extensions()}). Added for {@code snapshot-take}
 * (#341): a take out of {@code /sg snapshot} reuses this record shape (the
 * {@code salvage-withdraw} precedent) and needs to name the snapshot subject
 * and the as-of instant without overloading {@code target}, which already
 * carries the item material.
 */
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
        StoredItem item,
        Map<String, String> extensions) implements EventRecord {

    public ItemPickupRecord {
        extensions = extensions == null ? Map.of() : Map.copyOf(extensions);
    }

    /** Back-compat constructor for call sites that predate the {@code extensions}
     *  channel (#341). Leaves {@code extensions} empty. */
    public ItemPickupRecord(
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
            StoredItem item) {
        this(id, event, occurred, expiresAt, origin, source, location, server, target, amount, item, Map.of());
    }

    public static ItemPickupRecord of(RecordContext ctx, String target, int amount, StoredItem item) {
        return of(ctx, "pickup", target, amount, item);
    }

    /**
     * Same shape with an explicit event name, for events that reuse the pickup
     * record without being a world "pickup" - e.g. the destination side of an
     * automated hopper/dropper transfer ("transfer-in", #226), or an operator
     * take out of {@code /sg snapshot} ("snapshot-take", #341). Keeps
     * EventCatalog's name to record-class mapping honest with no codec change.
     */
    public static ItemPickupRecord of(RecordContext ctx, String event, String target, int amount,
            StoredItem item) {
        return new ItemPickupRecord(
                ctx.id(), event, ctx.occurred(), ctx.expiresAt(),
                ctx.origin(), ctx.source(), ctx.location(), ctx.server(), target, amount, item,
                ctx.extensions());
    }
}
