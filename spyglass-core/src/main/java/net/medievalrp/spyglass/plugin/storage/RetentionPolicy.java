package net.medievalrp.spyglass.plugin.storage;

import java.time.Instant;
import java.util.Map;
import org.jetbrains.annotations.ApiStatus;

/**
 * Per-event-type retention (#181). Resolves how long a record of a given event
 * type is kept before automatic deletion: a per-event override if one is
 * configured under {@code events.<name>.retention}, otherwise the global
 * {@code storage.retention} default.
 *
 * <p>Every {@link RecordStore} consults this when it stamps or prunes a row, so
 * the behaviour is identical across SQLite, MariaDB, ClickHouse, and Mongo - the
 * expiry is always {@code occurred + retentionFor(event)}.
 *
 * <p>"Keep forever" is expressed as {@link #NEVER_SECONDS}, a 100-year horizon.
 * That is effectively never for a forensic log while staying inside every
 * backend's date range, so a never-expiring type needs no per-backend TTL-skip
 * special case - it is simply stamped with a far-future expiry that no TTL or
 * prune sweep ever reaches.
 */
@ApiStatus.Internal
public final class RetentionPolicy {

    /** A type configured "never" / "0" is kept this long: ~100 years (then
     *  clamped to {@link #MAX_EXPIRY} below, so the actual horizon is ~2105). */
    public static final long NEVER_SECONDS = 100L * 365L * 24L * 60L * 60L;

    /**
     * Hard ceiling on any computed expiry. ClickHouse's table TTL is
     * {@code TTL toDateTime(expires_at)}, and {@code toDateTime()} is a 32-bit
     * DateTime capped at 2106-02-07; an expiry past that overflows and the row's
     * insert fails. So every expiry is clamped to just under it - which also caps
     * the keep-forever horizon uniformly across all four backends.
     */
    public static final Instant MAX_EXPIRY = Instant.parse("2105-01-01T00:00:00Z");

    private final long defaultSeconds;
    private final Map<String, Long> perEventSeconds;

    /**
     * @param defaultSeconds  global retention (the {@code storage.retention}
     *                        default), in seconds; must be positive.
     * @param perEventSeconds event-name -> retention seconds for the types that
     *                        override the default ({@link #NEVER_SECONDS} for a
     *                        never-expiring type). Types absent from the map
     *                        inherit {@code defaultSeconds}.
     */
    public RetentionPolicy(long defaultSeconds, Map<String, Long> perEventSeconds) {
        this.defaultSeconds = defaultSeconds;
        this.perEventSeconds = Map.copyOf(perEventSeconds);
    }

    /** A policy with no per-event overrides - every type uses {@code seconds}. */
    public static RetentionPolicy uniform(long seconds) {
        return new RetentionPolicy(seconds, Map.of());
    }

    /** Retention in seconds for {@code event} - its override, or the default. */
    public long secondsFor(String event) {
        Long override = perEventSeconds.get(event);
        return override != null ? override : defaultSeconds;
    }

    /** When a record of {@code event} fired at {@code occurred} expires, clamped
     *  to {@link #MAX_EXPIRY} so a never-horizon stays inside ClickHouse's TTL range. */
    public Instant expiresAt(Instant occurred, String event) {
        Instant expiry = occurred.plusSeconds(secondsFor(event));
        return expiry.isAfter(MAX_EXPIRY) ? MAX_EXPIRY : expiry;
    }

    /** The global default retention in seconds (used for the bulk prune sweep). */
    public long defaultSeconds() {
        return defaultSeconds;
    }

    /** The per-event overrides (event-name -> seconds); never null, may be empty. */
    public Map<String, Long> overrides() {
        return perEventSeconds;
    }
}
