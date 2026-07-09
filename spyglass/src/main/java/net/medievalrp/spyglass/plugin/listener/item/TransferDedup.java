package net.medievalrp.spyglass.plugin.listener.item;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jetbrains.annotations.ApiStatus;

/**
 * Time-windowed dedup for automated hopper/dropper transfers (#226).
 *
 * <p>{@code InventoryMoveItemEvent} fires once per item per hopper tick, so a
 * single farm hopper line would otherwise flood the store (the #197 disk
 * case). This collapses a repeating {@code (source, destination, material)}
 * transfer to at most one logged record per {@code windowMillis}. The window
 * is anchored on the first log in a burst: once a key logs, identical
 * transfers are suppressed until the window elapses, then the next one logs
 * and re-arms. It is the analogue of CoreProtect's {@code hopperAbort}.
 *
 * <p>Consulted off the main thread from the recorder's serializer stage, so it
 * is thread-safe (a {@link ConcurrentHashMap} with an atomic {@code compute}).
 * It holds no Bukkit state; {@link #purgeExpired()} drops stale keys and is
 * swept on the same async timer as the falling-block tracker.
 */
@ApiStatus.Internal
public final class TransferDedup {

    /** At most one transfer record per this long, per distinct key. */
    public static final long DEFAULT_WINDOW_MILLIS = 30_000L;

    /**
     * Safety cap on tracked keys. The live key count is bounded by the number
     * of active hopper pairs on the server, but a cap plus an opportunistic
     * purge keeps a pathological setup from growing the map without bound
     * between scheduled sweeps.
     */
    public static final int DEFAULT_MAX_ENTRIES = 100_000;

    /**
     * Identity of a transfer: the source and destination block coordinates
     * (always the same world for adjacent containers) plus the moved material.
     */
    public record Key(String world, int sx, int sy, int sz, int dx, int dy, int dz, String material) {
    }

    private final long windowMillis;
    private final int maxEntries;
    // key -> epoch millis of the log that opened the current window.
    private final Map<Key, Long> lastLogged = new ConcurrentHashMap<>();

    public TransferDedup() {
        this(DEFAULT_WINDOW_MILLIS, DEFAULT_MAX_ENTRIES);
    }

    public TransferDedup(long windowMillis, int maxEntries) {
        this.windowMillis = windowMillis;
        this.maxEntries = maxEntries;
    }

    /** True when this transfer should be logged now (production entry point). */
    public boolean shouldLog(Key key) {
        return shouldLog(key, System.currentTimeMillis());
    }

    // Package-private with an injected clock, for deterministic tests.
    boolean shouldLog(Key key, long nowMillis) {
        boolean[] log = {false};
        lastLogged.compute(key, (k, anchor) -> {
            if (anchor != null && nowMillis - anchor < windowMillis) {
                return anchor;      // still inside the window: suppress, keep the anchor
            }
            log[0] = true;
            return nowMillis;       // first of a fresh window: log and (re)arm
        });
        if (log[0] && lastLogged.size() > maxEntries) {
            purgeExpired(nowMillis);
        }
        return log[0];
    }

    /** Drop keys whose window has fully elapsed (keeping them would be a no-op). */
    public void purgeExpired() {
        purgeExpired(System.currentTimeMillis());
    }

    // Package-private with an injected clock, for deterministic tests.
    void purgeExpired(long nowMillis) {
        lastLogged.entrySet().removeIf(entry -> nowMillis - entry.getValue() >= windowMillis);
    }

    // Package-private, for tests.
    int size() {
        return lastLogged.size();
    }
}
