package net.medievalrp.spyglass.plugin.snapshot;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.logging.Logger;
import net.medievalrp.spyglass.api.event.StoredItem;
import net.medievalrp.spyglass.api.util.Duration;
import net.medievalrp.spyglass.api.util.EventIds;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.ApiStatus;

/**
 * Captures a player's inventory into a {@link PlayerSnapshot} (#341). Snapshots
 * are the only honest source for "what was this player carrying at time T" -
 * crafting, consuming, trades, and plugin-given items have no event trail a
 * rollback could replay from.
 *
 * <p>Capture is split the same way {@code SalvageCapturer} splits a container
 * capture: the live-world read ({@code getContents()}, the player's name/uuid,
 * and the capture instant) happens on the caller's thread - always the main
 * thread in production, since it touches the live {@code PlayerInventory} -
 * and is cheap, the same cost class as a container's per-chunk read. Every
 * per-slot clone, {@code serializeAsBytes}, hash, and the store write itself
 * run on this service's own single-thread executor, so a join/quit/death/
 * world-change/sweep capture never costs main-thread time beyond the array
 * read.
 *
 * <h2>Dirty check</h2>
 *
 * <p>An in-memory per-player last-content-hash cache (not the store) is
 * consulted on the hot path: a capture whose 64-bit content hash matches the
 * cached value writes nothing - the previous snapshot already is that state,
 * and {@code cause} is a label, not a reason to duplicate a row (join/quit/
 * death captures on an unmodified inventory are the common case). The cache
 * entry is warmed lazily on the first capture attempt for a player (one
 * {@link PlayerSnapshotStore#lastContentHash} read), then kept current from
 * every subsequent write. A quit evicts the entry (below) so the map does not
 * grow for players who left for good.
 *
 * <h2>Content hash</h2>
 *
 * <p>Hashing the full concatenated payload bytes of every occupied slot would
 * mean holding a buffer sized to the whole inventory's serialized NBT. Instead
 * each slot's normalized (count-stripped) payload is first reduced to a fixed
 * 16-byte SHA-256 prefix, and the outer digest streams over the small,
 * fixed-size (slot, count, 16-byte payload hash) tuples in slot order. The
 * result is the first 8 bytes of that outer digest, read as a big-endian long.
 */
@ApiStatus.Internal
public final class PlayerSnapshotService {

    private final PlayerSnapshotStore store;
    private final Duration interval;
    private final Duration retention;
    private final ExecutorService executor;
    private final Function<ItemStack, byte[]> serializer;
    private final Logger logger;

    // Confined to the executor thread: every read and write of this map
    // happens inside a task submitted to `executor`, which is single-threaded,
    // so no external synchronization is needed. A plain HashMap (not a
    // ConcurrentHashMap) documents that confinement rather than papering over
    // it with a lock nobody needs.
    private final Map<UUID, Long> lastHash = new HashMap<>();

    private volatile BukkitTask sweepTask;
    private volatile BukkitTask pruneTask;

    /** Upper bound on how long {@link #stop} waits for in-flight captures to
     *  finish before forcing the executor down. */
    private static final long SHUTDOWN_AWAIT_SECONDS = 5L;

    /** Once-per-hour prune cadence (#341): snapshot volume is low enough that
     *  a finer cadence buys nothing, and an hourly sweep keeps the anti-join
     *  orphan-payload cleanup (SQLite/MariaDB) or TTL bookkeeping (Mongo/CH)
     *  cheap per run. */
    private static final long PRUNE_PERIOD_TICKS = 20L * 60L * 60L;

    public PlayerSnapshotService(PlayerSnapshotStore store, Duration interval, Duration retention,
            Logger logger) {
        this(store, interval, retention, defaultExecutor(), ItemStack::serializeAsBytes, logger);
    }

    /** Visible for tests: inject a deterministic executor and a serializer
     *  that doesn't call the real (CraftBukkit-only) {@code serializeAsBytes}. */
    PlayerSnapshotService(PlayerSnapshotStore store, Duration interval, Duration retention,
            ExecutorService executor, Function<ItemStack, byte[]> serializer, Logger logger) {
        this.store = store;
        this.interval = interval;
        this.retention = retention;
        this.executor = executor;
        this.serializer = serializer;
        this.logger = logger;
    }

    private static ExecutorService defaultExecutor() {
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "spyglass-snapshot");
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newSingleThreadExecutor(factory);
    }

    /**
     * Capture {@code player}'s inventory, labeled with {@code cause} (one of
     * the {@code PlayerSnapshot.CAUSE_*} constants). Must be called from the
     * main thread - {@link Player#getInventory()} and {@code getContents()}
     * are live-world reads. Returns immediately; the serialize/hash/write
     * work is handed to this service's executor.
     */
    public void capture(Player player, String cause) {
        UUID uuid = player.getUniqueId();
        String name = player.getName();
        Instant capturedAt = Instant.now();
        // MAIN THREAD ONLY: read AND clone the live inventory here.
        // getContents() hands back a fresh 41-slot array (0-35 main, 36-39
        // armor, 40 offhand) but the ItemStacks in it can be CraftItemStack
        // mirrors of the live NMS stacks - touching those off-thread races
        // player inventory mutation (the #96/#97 snapshot-on-main rule, and
        // exactly what SalvageCapturer clones on main for). 41 clones is the
        // same cost class as a container's per-chunk read. Everything after
        // this - serializeAsBytes, hashing, and the store write - runs off
        // this thread, on clones only this service can see.
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            if (contents[i] != null) {
                contents[i] = contents[i].clone();
            }
        }

        executor.execute(() -> captureOffMain(uuid, name, capturedAt, cause, contents));
    }

    /** Capture every online player, tagged {@code CAUSE_SWEEP}. Intended to be
     *  called on the main thread by the repeating task {@link #start} installs. */
    public void sweep() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            capture(player, PlayerSnapshot.CAUSE_SWEEP);
        }
    }

    private void captureOffMain(UUID uuid, String name, Instant capturedAt, String cause,
            ItemStack[] contents) {
        try {
            List<SnapshotSlot> slots = new ArrayList<>();
            MessageDigest outer = sha256();
            for (int slot = 0; slot < contents.length; slot++) {
                ItemStack original = contents[slot];
                if (original == null || original.getType() == Material.AIR) {
                    continue; // empty slots are absent from the snapshot
                }
                // `original` is already a main-thread clone (capture()); this
                // second clone only isolates the setAmount(1) normalization,
                // so the interned payload for "64 cobblestone" and "12
                // cobblestone" is the same blob (SnapshotSlot javadoc).
                ItemStack normalized = original.clone();
                int count = normalized.getAmount();
                normalized.setAmount(1);
                byte[] raw = serializer.apply(normalized);
                byte[] payloadHash = sha256Prefix16(raw);
                String base64 = Base64.getEncoder().encodeToString(raw);
                StoredItem item = new StoredItem(slot, normalized.getType().name(), base64);
                slots.add(new SnapshotSlot(slot, count, item));

                outer.update(intBytes(slot));
                outer.update(intBytes(count));
                outer.update(payloadHash);
            }
            long contentHash = firstEightBytesAsLong(outer.digest());

            Long cached = lastHash.containsKey(uuid) ? lastHash.get(uuid) : warm(uuid);
            if (cached != null && cached == contentHash) {
                // Dirty-equal: the previous snapshot already is this state.
                // `cause` is a label, not a reason to duplicate the row.
                return;
            }

            // EventIds, not randomUUID: the ClickHouse store persists the id as
            // its embedded 62-bit sequence (EventIds.sequenceOf), which folds a
            // foreign/random UUID lossily and wrecks the id column's Delta
            // compression - the same rule every record id follows.
            PlayerSnapshot snapshot = new PlayerSnapshot(
                    EventIds.newId(), uuid, name, capturedAt, cause, contentHash, slots);
            store.save(snapshot);
            lastHash.put(uuid, contentHash);
        } catch (RuntimeException ex) {
            // Structured logging only, matching DeferredSerializer: a poison
            // item or a store hiccup must not escape to the executor's
            // default handler, and must not take the executor thread down.
            logger.warning("Spyglass snapshot capture failed for " + uuid + " (" + cause
                    + "); capture skipped: " + ex.getMessage());
        } finally {
            if (PlayerSnapshot.CAUSE_QUIT.equals(cause)) {
                // The player is gone; keeping their hash around only grows
                // the map for good. The next join re-warms from the store.
                lastHash.remove(uuid);
            }
        }
    }

    /** Lazily warm the cache from the store on the first capture attempt for
     *  a player. Runs on the executor thread, same as every other cache access. */
    private Long warm(UUID uuid) {
        OptionalLong previous = store.lastContentHash(uuid);
        Long value = previous.isPresent() ? previous.getAsLong() : null;
        lastHash.put(uuid, value);
        return value;
    }

    /**
     * Start the periodic sweep (every {@code interval}, on the main thread -
     * capture's main-thread part is cheap) and the hourly async prune (drops
     * rows older than {@code retention}).
     */
    public void start(JavaPlugin plugin) {
        long sweepTicks = Math.max(20L, interval.seconds() * 20L);
        this.sweepTask = Bukkit.getScheduler()
                .runTaskTimer(plugin, this::sweep, sweepTicks, sweepTicks);
        this.pruneTask = Bukkit.getScheduler()
                .runTaskTimerAsynchronously(plugin, this::pruneNow, PRUNE_PERIOD_TICKS, PRUNE_PERIOD_TICKS);
    }

    private void pruneNow() {
        try {
            Instant cutoff = Instant.now().minusSeconds(retention.seconds());
            int removed = store.prune(cutoff);
            if (removed > 0) {
                logger.fine("Spyglass snapshot prune removed " + removed
                        + " row(s) older than " + cutoff);
            }
        } catch (RuntimeException ex) {
            logger.warning("Spyglass snapshot prune failed: " + ex.getMessage());
        }
    }

    /** Cancel the scheduled tasks and shut down the executor, waiting briefly
     *  for an in-flight capture to finish (mirrors {@code DeferredSerializer#shutdown}). */
    public void stop() {
        if (sweepTask != null) {
            sweepTask.cancel();
            sweepTask = null;
        }
        if (pruneTask != null) {
            pruneTask.cancel();
            pruneTask = null;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(SHUTDOWN_AWAIT_SECONDS, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            // Every JVM implementation is required to provide SHA-256
            // (java.security.MessageDigest javadoc); this can't happen.
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private static byte[] sha256Prefix16(byte[] raw) {
        byte[] digest = sha256().digest(raw);
        byte[] prefix = new byte[16];
        System.arraycopy(digest, 0, prefix, 0, 16);
        return prefix;
    }

    private static byte[] intBytes(int value) {
        return new byte[] {
                (byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value
        };
    }

    private static long firstEightBytesAsLong(byte[] digest) {
        long result = 0L;
        for (int i = 0; i < 8; i++) {
            result = (result << 8) | (digest[i] & 0xFFL);
        }
        return result;
    }
}
