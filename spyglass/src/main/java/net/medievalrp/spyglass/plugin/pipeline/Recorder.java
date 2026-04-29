package net.medievalrp.spyglass.plugin.pipeline;

import net.medievalrp.spyglass.api.event.EventRecord;
import net.medievalrp.spyglass.api.util.Duration;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public interface Recorder {

    void record(EventRecord record);

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
}
