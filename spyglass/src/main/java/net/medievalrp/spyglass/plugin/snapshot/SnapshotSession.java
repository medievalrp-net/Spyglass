package net.medievalrp.spyglass.plugin.snapshot;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A resolved snapshot ready to show: what {@code /sg snapshot} computed, held
 * per-sender in a TTL cache so GUI clicks and the text-fallback
 * {@code /sg snapshot take <token> <slot>} act on exactly the state the
 * operator was shown. Sessions never write back; takes clone out of them.
 *
 * <p>Container sessions come from the reconstructor (certainty is the
 * forward-replay verdict, notes name what did not reconcile); player sessions
 * come from the snapshot store (always {@link Certainty#CERTAIN}, the capture
 * is exact - {@code capturedAt} tells the operator how close to T it landed).
 */
public record SnapshotSession(
        UUID token,
        Kind kind,
        String subjectLabel,
        Instant asOf,
        Instant capturedAt,
        String cause,
        Certainty certainty,
        List<String> notes,
        int containerRows,
        List<SnapshotSlot> slots) {

    public enum Kind { PLAYER, CONTAINER }

    /** The forward-replay verdict from #267: does the chain explain the live state. */
    public enum Certainty { CERTAIN, UNCERTAIN }

    public SnapshotSession {
        notes = notes == null ? List.of() : List.copyOf(notes);
        slots = slots == null ? List.of() : List.copyOf(slots);
    }
}
