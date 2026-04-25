package net.medievalrp.omniscience2.api.event;

import java.time.Instant;
import java.util.UUID;
import net.medievalrp.omniscience2.api.util.BlockLocation;

/**
 * The fields every {@link EventRecord} carries at the root: an id,
 * timestamps, origin, source, and location. Extractors build one per event
 * and hand it to the record's static {@code of(...)} factory along with
 * type-specific fields.
 */
public record RecordContext(
        UUID id,
        Instant occurred,
        Instant expiresAt,
        Origin origin,
        Source source,
        BlockLocation location) {

    public static RecordContext fresh(Instant occurred, Instant expiresAt,
                                      Origin origin, Source source, BlockLocation location) {
        return new RecordContext(UUID.randomUUID(), occurred, expiresAt, origin, source, location);
    }
}
