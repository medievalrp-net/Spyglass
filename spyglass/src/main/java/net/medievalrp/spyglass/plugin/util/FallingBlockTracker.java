package net.medievalrp.spyglass.plugin.util;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import org.jetbrains.annotations.ApiStatus;

/**
 * Short-lived map from "this cell is about to drop as a falling-block
 * entity" to "this is the player who caused it." Populated by
 * {@link FallingBlockCascade} when it walks up a column emitting
 * cascade break records, consumed by
 * {@link net.medievalrp.spyglass.plugin.listener.block.FallingBlockLandListener}
 * when the resulting {@code FallingBlock} entity lands somewhere and
 * fires {@code EntityChangeBlockEvent}.
 *
 * <p>Without this, the cascade restores the original column on
 * rollback but leaves orphaned sand/gravel at every landing position
 * — those cells were placed by Bukkit's gravity engine, not by any
 * listener Spyglass had registered, so they never made it into the
 * record store.
 *
 * <p>Keys are {@code (worldId, x, y, z)} of the cell that started
 * falling — i.e. the {@code FallingBlock}'s spawn / origin. Paper's
 * {@link org.bukkit.entity.Entity#getOrigin()} gives the listener the
 * exact spawn coordinate to look up.
 *
 * <p>TTL guards against the case where a falling-block entity never
 * lands at its origin: it shatters into an item over water/lava, falls
 * into the void, or is removed by {@code /kill}, an anti-cheat, a plot
 * border, a ride, … In all of those the matching {@link #consume} never
 * fires, so the entry can only leave the map by TTL eviction. Falls
 * complete in well under a second; 30 s is a wide safety margin.
 *
 * <p><b>Memory bound.</b> Because {@link #consume} only removes the
 * entries that <em>do</em> land, eviction of the rest is not optional —
 * it is the only thing that keeps the map from growing without limit
 * (a {@code //set air} under a desert over water cascades thousands of
 * cells whose falling blocks all shatter and never land). {@link #track}
 * therefore sweeps expired entries amortized, every {@link #PURGE_INTERVAL}
 * inserts, so the live set can never exceed roughly one purge-interval
 * plus whatever was tracked inside the last TTL window — independent of
 * uptime. The plugin also runs {@link #purgeExpired} on a timer so an
 * idle burst that stops tracking is still reclaimed promptly.
 */
@ApiStatus.Internal
public final class FallingBlockTracker {

    /** How long a tracked cell stays attributable after the cascade
     *  fires the break. Falling blocks should land in milliseconds;
     *  this is a wide margin for stuck entities (frozen, ridden, …). */
    private static final long TTL_MILLIS = 30_000L;

    /** Amortized self-purge cadence: every this many {@link #track} calls
     *  we sweep expired entries. Bounds the map without a scheduler even
     *  if the timer never runs. Small relative to a bulk-edit cascade so
     *  a giant op purges many times as it streams. */
    private static final int PURGE_INTERVAL = 1024;

    private static final ConcurrentMap<Key, Cell> CELLS = new ConcurrentHashMap<>();

    /** Inserts since the last amortized purge. */
    private static final AtomicInteger SINCE_PURGE = new AtomicInteger();

    /** Clock seam so tests can drive TTL eviction deterministically;
     *  production reads the wall clock. */
    private static volatile LongSupplier clock = System::currentTimeMillis;

    private FallingBlockTracker() {
    }

    /**
     * Register that {@code (worldId, x, y, z)} is about to drop as a
     * falling-block entity, caused by {@code playerId}/{@code
     * playerName}. The matching landing event will look this up via
     * {@link #consume}.
     *
     * <p>Sweeps expired entries every {@link #PURGE_INTERVAL} inserts so
     * a cascade of cells that never land can't grow the map without
     * bound.
     */
    public static void track(UUID worldId, int x, int y, int z,
                             UUID playerId, String playerName) {
        long now = clock.getAsLong();
        CELLS.put(new Key(worldId, x, y, z),
                new Cell(playerId, playerName, now + TTL_MILLIS));
        if (SINCE_PURGE.incrementAndGet() >= PURGE_INTERVAL) {
            SINCE_PURGE.set(0);
            purgeExpired();
        }
    }

    /**
     * Look up and remove the tracker entry for {@code (worldId, x, y,
     * z)}. Returns empty if not tracked, expired, or already consumed
     * — duplicate landings of the same origin (e.g. block lands, gets
     * pushed by piston, lands again) are attributed only on the first
     * land.
     */
    public static Optional<Tracked> consume(UUID worldId, int x, int y, int z) {
        Cell cell = CELLS.remove(new Key(worldId, x, y, z));
        if (cell == null) {
            return Optional.empty();
        }
        if (clock.getAsLong() > cell.expiresAt) {
            return Optional.empty();
        }
        return Optional.of(new Tracked(cell.playerId, cell.playerName));
    }

    /**
     * Sweep expired entries. Cheap when the map is small (no entries
     * within the live window). Run amortized from {@link #track} and on
     * a timer from the plugin; correctness of attribution does not
     * depend on it (consume TTL-checks on read), but the memory bound
     * does — an entry whose falling block never lands is only ever
     * removed here.
     */
    public static void purgeExpired() {
        long now = clock.getAsLong();
        CELLS.entrySet().removeIf(entry -> now > entry.getValue().expiresAt);
    }

    /** Test/diagnostic: how many tracked cells are currently live. */
    public static int size() {
        return CELLS.size();
    }

    /** Test hook — wipes the map. Production code calls this on disable
     *  so a {@code /reload} doesn't carry stale attribution across. */
    public static void clear() {
        CELLS.clear();
        SINCE_PURGE.set(0);
    }

    /** Test seam: override the clock used for TTL. Pass {@code null} to
     *  restore the wall clock. */
    static void setClockForTest(LongSupplier testClock) {
        clock = testClock == null ? System::currentTimeMillis : testClock;
    }

    /** Public DTO returned to the landing listener. */
    public record Tracked(UUID playerId, String playerName) {
    }

    private record Key(UUID worldId, int x, int y, int z) {
    }

    private record Cell(UUID playerId, String playerName, long expiresAt) {
    }
}
