package net.medievalrp.spyglass.plugin.snapshot;

import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import org.jetbrains.annotations.ApiStatus;

/**
 * Durable store for {@link PlayerSnapshot}s. A dedicated store (not the event
 * log) by the same pattern as {@code SalvageStore}: the rows carry full item
 * blobs, live under their own retention, and are read by point lookup rather
 * than the record query surface.
 *
 * <p>Implementations intern item payloads content-addressed (SHA-256/16 of the
 * raw {@code serializeAsBytes} bytes) so a player carrying the same kit for a
 * week costs one payload set, not one per snapshot. Every method is called off
 * the main thread. {@code save} must be read-your-writes: a subsequent
 * {@link #latestAtOrBefore} on any thread sees it (snapshot volume is low, so
 * ClickHouse uses synchronous inserts here, not the async event path).
 */
@ApiStatus.Internal
public interface PlayerSnapshotStore {

    /** Persist a capture. Interns payloads; idempotent re-intern must not throw. */
    void save(PlayerSnapshot snapshot);

    /** Newest snapshot with {@code capturedAt <= instant}, slots hydrated. */
    Optional<PlayerSnapshot> latestAtOrBefore(UUID player, Instant instant);

    /** Content hash of the player's newest snapshot, for dirty-cache warmup. */
    OptionalLong lastContentHash(UUID player);

    /**
     * Drop snapshots captured before {@code cutoff} and garbage-collect
     * payloads no snapshot references anymore. Returns removed snapshot rows.
     */
    int prune(Instant cutoff);

    /** Best-effort release of any backend resources. */
    default void close() {
    }
}
