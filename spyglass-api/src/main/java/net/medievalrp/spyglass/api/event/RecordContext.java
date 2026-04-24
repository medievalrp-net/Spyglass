package net.medievalrp.spyglass.api.event;

import java.time.Instant;
import java.util.UUID;
import net.medievalrp.spyglass.api.util.BlockLocation;

/**
 * The fields every {@link EventRecord} carries at the root: an id, schema
 * version, timestamps, origin, source, and location. Extractors build one of
 * these once per event and hand it to the record's static {@code of(...)}
 * factory along with type-specific fields.
 *
 * <p>Records are still constructed directly when code needs to override one
 * of these fields — the factory is the shorthand for the common path.
 */
public record RecordContext(
        UUID id,
        int schemaVersion,
        Instant occurred,
        Instant expiresAt,
        Origin origin,
        Source source,
        BlockLocation location) {

    /** Schema version for records written by this build. */
    public static final int SCHEMA = 1;

    /**
     * Mint a fresh context with a random id and {@link #SCHEMA} as the schema
     * version. Use this when recording a live event. Callers with an existing
     * id (e.g. replay paths) can construct {@link RecordContext} directly via
     * the canonical constructor instead.
     */
    public static RecordContext fresh(Instant occurred, Instant expiresAt,
                                      Origin origin, Source source, BlockLocation location) {
        return new RecordContext(UUID.randomUUID(), SCHEMA, occurred, expiresAt, origin, source, location);
    }
}
