package net.medievalrp.spyglass.plugin.pipeline;

import net.medievalrp.spyglass.api.event.EventRecord;
import net.medievalrp.spyglass.api.util.Duration;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public interface Recorder {

    void record(EventRecord record);

    /**
     * Bulk-record a batch of synthesized records. Implementations may
     * skip the per-record committed hook that {@link #record} fires —
     * this path is for internally-generated batches (the rollback audit
     * trail) where reactive per-record notification isn't needed and
     * firing one Bukkit event per record on the main thread would cost
     * more than the operation producing them. Default falls back to
     * per-record {@link #record} so test doubles work unchanged.
     */
    default void recordAll(java.util.List<EventRecord> records) {
        for (EventRecord record : records) {
            record(record);
        }
    }

    AsyncRecorder.ShutdownReport shutdown(Duration timeout);

    /**
     * Block until every record currently in the in-memory queue has been
     * acknowledged by the downstream store, or {@code timeout} elapses.
     * Records added after this call may or may not be drained by the time
     * it returns; the contract is "everything queued at call time is
     * persisted on success."
     *
     * <p>Used by the rollback path to close a read-your-writes hole: a
     * burst of {@code //set 0} events can sit in the queue for tens of
     * seconds while ClickHouse drains, and a rollback query during that
     * window would see only the records the store had committed,
     * partially restoring blocks.
     *
     * @return {@code true} if the snapshot drained, {@code false} on timeout
     */
    boolean flush(Duration timeout);

    /**
     * Snapshot of the on-disk overflow spill backlog, so a caller can tell
     * the operator how many records are still draining when a {@link #flush}
     * times out (#204). Default: an empty, disabled snapshot - a recorder
     * without a spill buffer, and test doubles, report no backlog.
     * {@link AsyncRecorder} overrides this with the live figures.
     */
    default AsyncRecorder.SpillSnapshot spillSnapshot() {
        return new AsyncRecorder.SpillSnapshot(false, 0L, 0L, 0L, 0L);
    }
}
