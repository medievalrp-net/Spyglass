package net.medievalrp.spyglass.api.event;

import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired when an {@link EventRecord} is committed to Spyglass's
 * durable ingest pipeline. Listen for this if your plugin needs to
 * react to forensic events as they flow through the system —
 * trigger an alert, kick off an audit hook, sync to an external
 * SIEM, etc.
 *
 * <p>This event fires <em>after</em> the record has been accepted
 * by the in-memory queue (and, when WAL durability is enabled,
 * after the WAL fsync). Final database persistence happens on the
 * drain thread shortly after; the event does not block on it.
 *
 * <h2>Threading</h2>
 *
 * <p>The event auto-detects its dispatch context: when fired from
 * the main server thread it is sync, otherwise async. Register
 * your listener accordingly:
 *
 * <pre>{@code
 * @EventHandler
 * public void onCommit(RecordCommittedEvent e) {
 *     // Safe on either thread; do not touch world state directly
 *     // when isAsynchronous() — hop via the scheduler first.
 * }
 * }</pre>
 *
 * <p>Most third-party plugins should treat the listener as
 * potentially-async and avoid mutating world state from inside it.
 * Use {@code Bukkit.getScheduler().runTask(plugin, ...)} to hop
 * back to the main thread if needed.
 *
 * <h2>Lifecycle</h2>
 *
 * <p>Cancellation is intentionally not supported: by the time this
 * event fires the record is already on the durable pipeline.
 * Filtering must happen upstream in your own listeners.
 */
public final class RecordCommittedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final EventRecord record;

    public RecordCommittedEvent(EventRecord record) {
        super(!Bukkit.isPrimaryThread());
        this.record = record;
    }

    /** The record that was just committed; never null. */
    public EventRecord record() {
        return record;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
