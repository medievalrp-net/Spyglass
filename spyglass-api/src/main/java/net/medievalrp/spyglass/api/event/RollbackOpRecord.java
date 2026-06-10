package net.medievalrp.spyglass.api.event;

import java.time.Instant;
import java.util.UUID;
import net.medievalrp.spyglass.api.util.BlockLocation;

/**
 * One record per completed rollback / restore / undo operation: who ran
 * it, when, in which mode, and — inside {@code reference} — the resolved
 * query, time ceiling, per-world bounding boxes, and applied/skipped
 * counts (a versioned BSON blob; see the core {@code UndoReferenceBson}).
 *
 * <p>This row is the durable identity of the operation. The per-block
 * {@code rolled-place} / {@code rolled-break} entries that searches show
 * are <em>synthesized</em> from it on demand: the operation's stored
 * query, bounded by its ceiling, re-identifies exactly the records the
 * operation covered, so nothing per-block needs to be written. {@code
 * location} is the min corner of the first world's bounding box —
 * spatial reasoning uses the boxes in the blob, not this point.
 *
 * <p>{@code target()} carries the mode (ROLLBACK / RESTORE) so generic
 * renderers display something meaningful.
 */
public record RollbackOpRecord(
        UUID id,
        String event,
        Instant occurred,
        Instant expiresAt,
        Origin origin,
        Source source,
        BlockLocation location,
        String server,
        String mode,
        String reference) implements EventRecord {

    @Override
    public String target() {
        return mode;
    }

    public static RollbackOpRecord of(RecordContext ctx, String mode, String reference) {
        return new RollbackOpRecord(
                ctx.id(), "rollback-op", ctx.occurred(), ctx.expiresAt(),
                ctx.origin(), ctx.source(), ctx.location(), ctx.server(), mode, reference);
    }
}
