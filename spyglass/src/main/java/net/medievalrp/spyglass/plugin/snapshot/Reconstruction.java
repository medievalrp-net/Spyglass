package net.medievalrp.spyglass.plugin.snapshot;

import java.util.List;

/**
 * The output of {@link SnapshotReconstructor}: the container's slots as of the
 * requested instant, plus how much the #267 forward-replay trusts them.
 *
 * <p>{@code slots} holds only the occupied slots of the reconstructed
 * (reverse-applied) state. {@code certainty} is {@code CERTAIN} only when the
 * forward-replay reproduced the live container exactly and no hard trigger
 * (legacy rows, absent container, self-mutating block) fired; otherwise it is
 * {@code UNCERTAIN} and {@code notes} names, in operator-readable text, every
 * reason it could not be certain. {@code mismatches} is the same information as
 * structured data for the view/audit layer: each forward-replay step that did
 * not line up and each slot whose live contents the records failed to explain.
 */
public record Reconstruction(
        List<SnapshotSlot> slots,
        SnapshotSession.Certainty certainty,
        List<String> notes,
        List<Mismatch> mismatches) {

    public Reconstruction {
        slots = slots == null ? List.of() : List.copyOf(slots);
        notes = notes == null ? List.of() : List.copyOf(notes);
        mismatches = mismatches == null ? List.of() : List.copyOf(mismatches);
    }

    /** True when the forward-replay verified the reconstruction against live. */
    public boolean certain() {
        return certainty == SnapshotSession.Certainty.CERTAIN;
    }

    /**
     * One discrepancy the forward-replay found. {@code expected} is the state
     * the records predict, {@code actual} is what was actually there, both as
     * material labels ({@code "empty"} for a cleared slot). A pure component
     * cannot decode blobs, so labels are material-level; the slot index is
     * always exact.
     */
    public record Mismatch(int slot, Kind kind, String expected, String actual) {

        /**
         * {@code REPLAY_STEP}: a record's recorded before-state did not match
         * the state the chain had reached at that point (a row out of order or
         * tampered). {@code END_STATE}: after replaying every record the result
         * did not equal the live container (a change never written to the log).
         */
        public enum Kind { REPLAY_STEP, END_STATE }
    }
}
