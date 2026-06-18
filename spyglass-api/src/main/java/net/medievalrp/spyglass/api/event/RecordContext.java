package net.medievalrp.spyglass.api.event;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.api.util.EventIds;

/**
 * The fields every {@link EventRecord} carries at the root: an id,
 * timestamps, origin, source, location, and the recording server's
 * identifier. Extractors build one per event and hand it to the record's
 * static {@code of(...)} factory along with type-specific fields.
 *
 * <p>It also carries an optional bag of {@code extensions} — string
 * key/value metadata an integrating plugin attaches via {@link
 * #withExtension} (e.g. a chat channel) so the field is stored, searchable,
 * and displayable as first-class data rather than overloading a core field
 * like {@code target}. Records opt in by exposing an {@code extensions}
 * component; those that don't return an empty map via {@link
 * EventRecord#extensions()}.
 */
public record RecordContext(
        UUID id,
        Instant occurred,
        Instant expiresAt,
        Origin origin,
        Source source,
        BlockLocation location,
        String server,
        Map<String, String> extensions) {

    public RecordContext {
        extensions = extensions == null ? Map.of() : Map.copyOf(extensions);
    }

    public static RecordContext fresh(Instant occurred, Instant expiresAt,
                                      Origin origin, Source source, BlockLocation location,
                                      String server) {
        // Time-ordered v7 instead of random v4: the id column is the
        // single largest consumer of store disk, and v4 entropy is
        // incompressible (see EventIds).
        return new RecordContext(
                EventIds.newId(), occurred, expiresAt, origin, source, location, server, Map.of());
    }

    /**
     * A copy of this context with {@code key=value} added to its extensions.
     * Integrating plugins call this before the record's {@code of(...)}
     * factory so the field rides into storage:
     * {@code ChatRecord.of(ctx.withExtension("channel", "#OOC"), ...)}.
     */
    public RecordContext withExtension(String key, String value) {
        Map<String, String> next = new HashMap<>(extensions);
        next.put(key, value);
        return new RecordContext(id, occurred, expiresAt, origin, source, location, server, next);
    }
}
