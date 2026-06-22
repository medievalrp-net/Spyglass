package net.medievalrp.spyglass.plugin.util;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
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
 * <p>TTL guards against the rare case where a falling-block entity is
 * frozen by /tp, /freeze, plot border, ride, …; without TTL eviction
 * a stuck entity would leave a row in the map indefinitely. Falls
 * normally complete in well under a second; 30 s is a wide safety
 * margin.
 */
@ApiStatus.Internal
public final class FallingBlockTracker {

    /** How long a tracked cell stays attributable after the cascade
     *  fires the break. Falling blocks should land in milliseconds;
     *  this is a wide margin for stuck entities (frozen, ridden, …). */
    private static final long TTL_MILLIS = 30_000L;

    private static final ConcurrentMap<Key, Cell> CELLS = new ConcurrentHashMap<>();

    private FallingBlockTracker() {
    }

    /**
     * Register that {@code (worldId, x, y, z)} is about to drop as a
     * falling-block entity, caused by {@code playerId}/{@code
     * playerName}. The matching landing event will look this up via
     * {@link #consume}.
     */
    public static void track(UUID worldId, int x, int y, int z,
                             UUID playerId, String playerName) {
        long expiresAt = System.currentTimeMillis() + TTL_MILLIS;
        CELLS.put(new Key(worldId, x, y, z),
                new Cell(playerId, playerName, expiresAt));
    }

    /**
     * Package-private overload for tests: inserts a cell with an
     * explicit {@code expiresAt} epoch-millis value so tests can
     * simulate already-expired entries without sleeping.
     */
    static void track(UUID worldId, int x, int y, int z,
                      UUID playerId, String playerName, long expiresAt) {
        CELLS.put(new Key(worldId, x, y, z),
                new Cell(playerId, playerName, expiresAt));
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
        if (System.currentTimeMillis() > cell.expiresAt) {
            return Optional.empty();
        }
        return Optional.of(new Tracked(cell.playerId, cell.playerName));
    }

    /**
     * Lazy purge — sweeps expired entries. Cheap when the map is small
     * (no entries within the live window). Callers can run this on a
     * timer or just before a {@link #track} burst; not required for
     * correctness because {@link #consume} TTL-checks on read.
     */
    public static void purgeExpired() {
        long now = System.currentTimeMillis();
        CELLS.entrySet().removeIf(entry -> now > entry.getValue().expiresAt);
    }

    /** Test/diagnostic: how many tracked cells are currently live. */
    public static int size() {
        return CELLS.size();
    }

    /** Test hook — wipes the map. Production code never calls this. */
    public static void clear() {
        CELLS.clear();
    }

    /** Public DTO returned to the landing listener. */
    public record Tracked(UUID playerId, String playerName) {
    }

    private record Key(UUID worldId, int x, int y, int z) {
    }

    private record Cell(UUID playerId, String playerName, long expiresAt) {
    }
}
