package net.medievalrp.spyglass.plugin.salvage;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks salvage-item removals that have been dispatched to the async store
 * write but whose acknowledgement has not yet arrived. Prevents a dupe exploit
 * where a player takes an item, presses Back (which re-reads the DB before the
 * write lands), and takes the same item a second time.
 *
 * <p>Identity key: {@code snapshotId + "|" + containerSlot}. The container
 * slot ({@link net.medievalrp.spyglass.api.event.StoredItem#slot()}) is the
 * original physical chest slot and is stable across re-reads from the DB.
 *
 * <p>Thread-safety: all methods are safe to call from both the Bukkit main
 * thread (GUI click handler) and the async store executor (write continuation).
 */
final class InFlightTracker {

    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    /**
     * Mark a slot as in-flight. Call on the main thread before dispatching the
     * async write. Returns {@code true} if the slot was not already in-flight
     * (i.e. the mark was accepted). Returns {@code false} if the slot is
     * already tracked - the caller should refuse the take to prevent duplication.
     */
    boolean markInFlight(UUID snapshotId, int containerSlot) {
        return inFlight.add(key(snapshotId, containerSlot));
    }

    /**
     * Clear the in-flight mark for a slot. Call from the async write continuation
     * once the store write has succeeded or definitively failed.
     */
    void clear(UUID snapshotId, int containerSlot) {
        inFlight.remove(key(snapshotId, containerSlot));
    }

    /**
     * Returns {@code true} if the slot is currently in-flight (write dispatched,
     * not yet acknowledged).
     */
    boolean isInFlight(UUID snapshotId, int containerSlot) {
        return inFlight.contains(key(snapshotId, containerSlot));
    }

    /**
     * The marks held right now. A store read started after this call can
     * still return a row whose write settles - clearing its mark - before
     * the read's result is filtered; checking this snapshot as well hides
     * that row. Call on the main thread before dispatching the read.
     */
    Set<String> snapshot() {
        return Set.copyOf(inFlight);
    }

    /** In flight now, or held in {@code earlier} (a {@link #snapshot()}). */
    boolean isInFlight(UUID snapshotId, int containerSlot, Set<String> earlier) {
        String key = key(snapshotId, containerSlot);
        return inFlight.contains(key) || earlier.contains(key);
    }

    /**
     * Remove all in-flight marks for the given snapshot. Call when a snapshot is
     * fully deleted so stale marks do not accumulate.
     */
    void clearAll(UUID snapshotId) {
        String prefix = snapshotId.toString() + "|";
        inFlight.removeIf(k -> k.startsWith(prefix));
    }

    private static String key(UUID snapshotId, int containerSlot) {
        return snapshotId.toString() + "|" + containerSlot;
    }
}
