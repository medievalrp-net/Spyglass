package net.medievalrp.spyglass.plugin.snapshot;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.medievalrp.spyglass.api.event.ContainerDepositRecord;
import net.medievalrp.spyglass.api.event.ContainerWithdrawRecord;
import net.medievalrp.spyglass.api.event.EventRecord;
import net.medievalrp.spyglass.api.event.StoredItem;
import net.medievalrp.spyglass.api.util.EventIds;

/**
 * Reconstructs a single container inventory as of an instant T and reports how
 * much to trust the result, using the #267 reverse-apply plus forward-replay
 * verification.
 *
 * <p>The algorithm is deliberately pure: it takes records and the live slot
 * array as plain data and touches no Bukkit type, so it unit-tests without
 * {@code mockStatic}. Two passes:
 *
 * <ol>
 *   <li><b>Reverse-apply</b> the container-family records newest-first onto a
 *       copy of the live contents ({@code slot := beforeItem}); the last op
 *       processed per slot is the oldest, so each slot lands on the state it
 *       held at T. Deposit and withdraw records both carry the slot's exact
 *       {@code (before, after)} state pair, so rewind direction does not
 *       matter.</li>
 *   <li><b>Forward-replay</b> the same records oldest-first from the
 *       reconstructed state: at each op the current slot must already equal
 *       {@code beforeItem}, then it becomes {@code afterItem}. Mismatches are
 *       collected, not thrown. If the replay reproduces the live container
 *       exactly and nothing forced doubt, the reconstruction is
 *       {@link SnapshotSession.Certainty#CERTAIN}; otherwise it is
 *       {@link SnapshotSession.Certainty#UNCERTAIN} with a note per unexplained
 *       slot.</li>
 * </ol>
 *
 * <p>Slot states are compared by their serialized payload string, with a null
 * {@link StoredItem} and an {@code AIR} item both treated as empty - the same
 * equality the rollback engine's {@code ContainerSlotWrite} guard uses.
 *
 * <p><b>One inventory per call.</b> Double chests are the caller's problem: it
 * resolves the two half locations, reconstructs each half separately against
 * its own per-half local slots (0-26), and merges the two results into the
 * 54-slot view. This component never sees a double chest.
 *
 * <p><b>Count semantics.</b> The output {@link SnapshotSlot}s carry the record's
 * {@link StoredItem} as-is and set {@code count} to {@link #COUNT_IN_BLOB}
 * (see the constant), because for container records the real stack size lives
 * inside the serialized blob, not in the record's {@code amount} field.
 */
public final class SnapshotReconstructor {

    /**
     * {@code count} value on reconstructed container slots. The container
     * deposit/withdraw listener serializes the full post-click slot state into
     * {@code beforeItem}/{@code afterItem} (ContainerTransactionListener,
     * roughly lines 141-178: the {@code (before, after)} pair is the slot's
     * exact state, and the record's {@code amount} field is only the
     * transferred delta - e.g. withdrawing 20 from a stack of 64 records
     * amount 20 with an after-blob of 44). So the real stack size already lives
     * in {@link StoredItem#data()}, unlike player snapshots where the interned
     * blob is normalized to amount 1 and {@link SnapshotSlot#count()} is the
     * authority. A pure component cannot decode the blob to read that size, so
     * count is left 0 and callers must take the amount from
     * {@code decode(item.data())} directly - never {@code setAmount(count)} on a
     * container-mode slot.
     */
    public static final int COUNT_IN_BLOB = 0;

    /**
     * Chronological order: primary the wall-clock instant, tie-broken by the
     * id's embedded sequence. Records minted within the same millisecond (a
     * SWAP_WITH_CURSOR withdraw+deposit pair, a shift-click fan-out) share an
     * {@code occurred}, so the monotonic id sequence is what keeps their order
     * stable and reversible.
     */
    private static final Comparator<SlotOp> CHRONO = Comparator
            .comparing(SlotOp::occurred)
            .thenComparingLong(op -> EventIds.sequenceOf(op.id()));

    private SnapshotReconstructor() {
    }

    /**
     * Reconstruct one container inventory as of {@code t}.
     *
     * @param windowRecords every record pinned to this container location with
     *                       {@code occurred} in {@code [t, now]}, in any order;
     *                       non-container records and records older than
     *                       {@code t} are ignored
     * @param liveContents   the container's current contents by slot, null per
     *                       empty slot, captured on the main thread by the
     *                       caller; when {@code containerPresent} is false pass
     *                       an empty (all-null) array
     * @param size           the container's slot count (the authoritative bound)
     * @param t              the instant to reconstruct; may be null to skip the
     *                       defensive window filter
     * @param containerPresent whether the live container still exists; false
     *                       forces UNCERTAIN and skips forward-replay (there is
     *                       no live state to verify against)
     * @param selfMutating   whether the block mutates its own contents without
     *                       logging (furnace/brewing/campfire family); the
     *                       caller decides from the block type, and a true value
     *                       forces UNCERTAIN
     */
    public static Reconstruction reconstruct(
            List<EventRecord> windowRecords,
            StoredItem[] liveContents,
            int size,
            Instant t,
            boolean containerPresent,
            boolean selfMutating) {

        int slots = Math.max(0, size);
        StoredItem[] live = normalize(liveContents, slots);

        List<SlotOp> ops = new ArrayList<>();
        boolean legacy = false;     // any slot < 0 row (pre-#268 per-slot logging)
        boolean outOfRange = false; // a record slot beyond the current size (shape changed)
        for (EventRecord record : windowRecords == null ? List.<EventRecord>of() : windowRecords) {
            SlotOp op = SlotOp.of(record);
            if (op == null) {
                continue;
            }
            if (t != null && op.occurred().isBefore(t)) {
                continue;
            }
            if (op.slot() < 0) {
                legacy = true;
                continue;
            }
            if (op.slot() >= slots) {
                outOfRange = true;
                continue;
            }
            ops.add(op);
        }

        // Pass 1 - reverse-apply newest-first back to the state held at T.
        StoredItem[] candidate = live.clone();
        List<SlotOp> newestFirst = new ArrayList<>(ops);
        newestFirst.sort(CHRONO.reversed());
        for (SlotOp op : newestFirst) {
            candidate[op.slot()] = op.before();
        }

        List<String> notes = new ArrayList<>();
        List<Reconstruction.Mismatch> mismatches = new ArrayList<>();

        // Pass 2 - forward-replay from the reconstructed state and check it
        // reproduces live. Skipped when the container is gone: there is no live
        // state to reconcile against, and the reconstruction is already flagged
        // UNCERTAIN below.
        if (containerPresent) {
            StoredItem[] replay = candidate.clone();
            List<SlotOp> oldestFirst = new ArrayList<>(ops);
            oldestFirst.sort(CHRONO);
            for (SlotOp op : oldestFirst) {
                if (!sameState(replay[op.slot()], op.before())) {
                    mismatches.add(new Reconstruction.Mismatch(
                            op.slot(), Reconstruction.Mismatch.Kind.REPLAY_STEP,
                            label(op.before()), label(replay[op.slot()])));
                    notes.add("slot " + op.slot() + ": a record expected " + label(op.before())
                            + " but the chain had " + label(replay[op.slot()])
                            + " (record out of order or tampered)");
                }
                replay[op.slot()] = op.after();
            }
            for (int i = 0; i < slots; i++) {
                if (!sameState(replay[i], live[i])) {
                    mismatches.add(new Reconstruction.Mismatch(
                            i, Reconstruction.Mismatch.Kind.END_STATE,
                            label(replay[i]), label(live[i])));
                    notes.add("slot " + i + ": live " + label(live[i])
                            + " does not match the reconstructed " + label(replay[i])
                            + " (change not in the log)");
                }
            }
        }

        // Hard triggers - UNCERTAIN regardless of how cleanly the replay ran.
        if (legacy) {
            notes.add("history predates per-slot logging (legacy slot < 0 rows); "
                    + "the reconstruction may be incomplete");
        }
        if (outOfRange) {
            notes.add("a record references a slot beyond the current container size; "
                    + "the container's shape changed");
        }
        if (!containerPresent) {
            notes.add("container no longer present, reconstructed from records against empty");
        }
        if (selfMutating) {
            notes.add("self-mutating container (furnace/brewing/campfire family); "
                    + "its contents cannot be verified from records");
        }

        boolean uncertain = legacy || outOfRange || !containerPresent || selfMutating
                || !mismatches.isEmpty();
        SnapshotSession.Certainty certainty = uncertain
                ? SnapshotSession.Certainty.UNCERTAIN
                : SnapshotSession.Certainty.CERTAIN;

        List<SnapshotSlot> result = new ArrayList<>();
        for (int i = 0; i < slots; i++) {
            StoredItem item = candidate[i];
            if (isEmpty(item)) {
                continue;
            }
            result.add(new SnapshotSlot(i, COUNT_IN_BLOB, item));
        }
        return new Reconstruction(result, certainty, notes, mismatches);
    }

    private static StoredItem[] normalize(StoredItem[] live, int slots) {
        StoredItem[] out = new StoredItem[slots];
        if (live != null) {
            System.arraycopy(live, 0, out, 0, Math.min(slots, live.length));
        }
        return out;
    }

    /** A cleared slot: a null item, or one whose material is absent or AIR. */
    private static boolean isEmpty(StoredItem item) {
        if (item == null) {
            return true;
        }
        String material = item.material();
        return material == null || material.equals("AIR");
    }

    /** Two slot states are equal when both are empty or their blobs match. */
    private static boolean sameState(StoredItem a, StoredItem b) {
        boolean emptyA = isEmpty(a);
        boolean emptyB = isEmpty(b);
        if (emptyA || emptyB) {
            return emptyA && emptyB;
        }
        return Objects.equals(a.data(), b.data());
    }

    private static String label(StoredItem item) {
        if (isEmpty(item)) {
            return "empty";
        }
        String material = item.material();
        return material == null ? "unknown" : material;
    }

    /**
     * A container deposit or withdraw flattened to the fields reconstruction
     * needs. Both record types carry the identical {@code (slot, beforeItem,
     * afterItem)} state pair; the direction (which way items moved) is
     * irrelevant to rewinding a slot.
     */
    private record SlotOp(UUID id, Instant occurred, int slot, StoredItem before, StoredItem after) {

        static SlotOp of(EventRecord record) {
            if (record instanceof ContainerDepositRecord d) {
                return new SlotOp(d.id(), d.occurred(), d.slot(), d.beforeItem(), d.afterItem());
            }
            if (record instanceof ContainerWithdrawRecord w) {
                return new SlotOp(w.id(), w.occurred(), w.slot(), w.beforeItem(), w.afterItem());
            }
            return null;
        }
    }
}
