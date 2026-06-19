package net.medievalrp.spyglass.plugin.pipeline;

import java.util.function.Consumer;
import java.util.function.IntSupplier;
import net.medievalrp.spyglass.api.event.EventRecord;
import org.jetbrains.annotations.ApiStatus;

/**
 * Committed-hook target that publishes {@code RecordCommittedEvent} to
 * Bukkit, but only when something is actually listening for it.
 *
 * <p>The committed hook runs synchronously on the recording thread — the
 * main thread for live listeners — so it sits on the ingest hot path of
 * <em>every</em> event type. When no plugin has registered a
 * {@code RecordCommittedEvent} listener (the common case unless a
 * companion plugin consumes it), allocating the event and dispatching it
 * through an empty handler list is pure per-record waste, folded into the
 * profiler self-time of every listener. Guarding on the live
 * registered-listener count skips both the allocation and the dispatch in
 * that case, and fires exactly as before the moment any listener
 * registers — Bukkit's {@code HandlerList} re-bakes its registered array
 * on every (un)register, so the count is always current and the check is
 * cheap.
 *
 * <p>The two collaborators are injected so the guard is unit-testable
 * headless, without a running server: {@code registeredListenerCount}
 * supplies {@code
 * RecordCommittedEvent.getHandlerList().getRegisteredListeners().length}
 * and {@code dispatch} performs the actual
 * {@code Bukkit.getPluginManager().callEvent(...)}. This mirrors why the
 * committed hook exists at all — to keep the recorder free of a direct
 * Bukkit call so it stays headless-testable.
 */
@ApiStatus.Internal
public final class RecordCommittedPublisher implements Consumer<EventRecord> {

    private final IntSupplier registeredListenerCount;
    private final Consumer<EventRecord> dispatch;

    public RecordCommittedPublisher(IntSupplier registeredListenerCount,
                                    Consumer<EventRecord> dispatch) {
        this.registeredListenerCount = registeredListenerCount;
        this.dispatch = dispatch;
    }

    @Override
    public void accept(EventRecord record) {
        if (registeredListenerCount.getAsInt() > 0) {
            dispatch.accept(record);
        }
    }
}
