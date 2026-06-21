package net.medievalrp.spyglass.api.event;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.medievalrp.spyglass.api.util.BlockLocation;

/**
 * A generic, integrator-defined event. Third-party plugins register a custom
 * event name via {@link net.medievalrp.spyglass.api.SpyglassApi#registerEvent}
 * and log one of these per occurrence.
 *
 * <p>Deliberately built from components that already exist on every storage
 * backend so a custom event needs no schema change: {@code target} (a summary
 * string), {@code message} (the primary freeform text — e.g. a voice
 * transcript), and the {@code extensions} bag (arbitrary string key/value
 * data, searchable as {@code extensions.<key>}). Not {@link
 * net.medievalrp.spyglass.api.rollback.Rollbackable} — a custom event has no
 * inverse effect.
 */
public record CustomRecord(
        UUID id,
        String event,
        Instant occurred,
        Instant expiresAt,
        Origin origin,
        Source source,
        BlockLocation location,
        String server,
        String target,
        String message,
        Map<String, String> extensions) implements EventRecord {

    public CustomRecord {
        extensions = extensions == null ? Map.of() : Map.copyOf(extensions);
    }

    /**
     * Build a custom-event record. {@code data} is merged into the context's
     * extensions bag, so both context-supplied metadata and the per-call data
     * ride into storage together.
     *
     * @param eventName the registered custom event name (see
     *                  {@link net.medievalrp.spyglass.api.SpyglassApi#registerEvent})
     * @param target    short summary shown inline after the verb (may be null)
     * @param message   primary freeform text, searchable via {@code m:} (may be null)
     * @param data      arbitrary string key/value bag (may be null/empty)
     */
    public static CustomRecord of(RecordContext ctx, String eventName, String target,
                                  String message, Map<String, String> data) {
        Map<String, String> merged;
        if (data == null || data.isEmpty()) {
            merged = ctx.extensions();
        } else {
            merged = new HashMap<>(ctx.extensions());
            merged.putAll(data);
        }
        return new CustomRecord(
                ctx.id(), eventName, ctx.occurred(), ctx.expiresAt(),
                ctx.origin(), ctx.source(), ctx.location(), ctx.server(),
                target, message, merged);
    }
}
