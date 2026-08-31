package net.medievalrp.spyglass.plugin.snapshot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;
import net.medievalrp.spyglass.api.event.StoredItem;
import net.medievalrp.spyglass.api.util.EventIds;
import net.medievalrp.spyglass.plugin.storage.SqliteRecordStore;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * On-disk footprint of player inventory snapshots at server scale, measured
 * in-JVM against a real SQLite database - no server, no Docker.
 *
 * <p>The question this answers: what does the snapshot feature cost a busy
 * server, and does it compound? Three scenarios, each seeded with a realistic
 * inventory population and then swept repeatedly:
 *
 * <ul>
 *   <li><b>idle</b> - nobody's inventory changes. The service's content-hash
 *       dirty check should make this free, so this measures whether "1000
 *       players standing around" costs anything at all.</li>
 *   <li><b>stable kits</b> - players gather and consume, so stack COUNTS
 *       change every sweep but the item payloads themselves repeat. This is
 *       the case payload interning is designed for.</li>
 *   <li><b>durability drift</b> - the same, except each player's tools and
 *       armor take damage every sweep. Damage lives inside the serialized
 *       payload, so every drifting item is a fresh interned blob: interning
 *       cannot dedupe it. This is the worst realistic case.</li>
 * </ul>
 *
 * <p>Measured bytes are the whole database delta (tables plus indexes, after
 * a WAL checkpoint), divided by the captures that actually got written, then
 * projected to a 1000-player server. Not part of the default test cycle; run
 * with {@code ./gradlew :spyglass:snapshotStorageBench}.
 */
@Tag("snapshot-bench")
class PlayerSnapshotStorageBench {

    /** Players simulated per scenario. Scaled up in the projection. */
    private static final int PLAYERS = 200;
    /** Sweeps simulated. At the 1m default this is a bit over an hour. */
    private static final int SWEEPS = 60;

    /** Occupied slots for an active player (of 41: 36 main, 4 armor, 1 offhand). */
    private static final int OCCUPIED_SLOTS = 28;
    /** Of those, the gear that carries damage: 4 armor, a weapon, a tool. */
    private static final int GEAR_SLOTS = 6;

    /** Raw serialized size of a plain stack (cobblestone, bread, torches). */
    private static final int PLAIN_PAYLOAD_BYTES = 30;
    /** Raw serialized size of enchanted/named RP gear. */
    private static final int GEAR_PAYLOAD_BYTES = 250;

    /** Distinct plain items in circulation server-wide (what interning folds). */
    private static final int PLAIN_ITEM_VARIETY = 300;

    private static final int PROJECT_PLAYERS = 1000;
    private static final int SWEEPS_PER_DAY = 24 * 60; // the 1m default
    private static final int RETENTION_DAYS = 30;      // the shipped default

    @TempDir
    Path dir;

    @Test
    void footprintAtServerScale() throws IOException {
        System.out.printf(Locale.ROOT,
                "%n=== player snapshot footprint (%d players x %d sweeps, %d occupied slots) ===%n",
                PLAYERS, SWEEPS, OCCUPIED_SLOTS);
        run("idle (no inventory changes)", false, false);
        run("stable kits (counts change, payloads repeat)", true, false);
        run("durability drift (gear damaged every sweep)", true, true);
    }

    private void run(String label, boolean countsChange, boolean durabilityDrifts) throws IOException {
        Path db = dir.resolve("bench-" + Math.abs(label.hashCode()) + ".db");
        SqliteRecordStore store = new SqliteRecordStore(db);
        SqlitePlayerSnapshotStore snapshots = new SqlitePlayerSnapshotStore(store);
        // Deterministic: same population and same churn every run.
        Random random = new Random(42);

        List<UUID> players = new ArrayList<>(PLAYERS);
        for (int i = 0; i < PLAYERS; i++) {
            players.add(UUID.nameUUIDFromBytes(("player-" + i).getBytes(StandardCharsets.UTF_8)));
        }

        // Baseline capture for everyone, so the measured delta is steady-state
        // sweeping rather than first-contact.
        long baseMillis = 1_700_000_000_000L;
        for (int p = 0; p < PLAYERS; p++) {
            snapshots.save(capture(players.get(p), p, 0, Instant.ofEpochMilli(baseMillis),
                    false, false, random));
        }
        checkpoint(store);
        long before = dbBytes(db);

        int written = 0;
        for (int sweep = 1; sweep <= SWEEPS; sweep++) {
            for (int p = 0; p < PLAYERS; p++) {
                // The dirty check suppresses a capture whose contents are
                // identical to the last one: model it, don't re-implement it.
                if (!countsChange && !durabilityDrifts) {
                    continue;
                }
                snapshots.save(capture(players.get(p), p, sweep,
                        Instant.ofEpochMilli(baseMillis + sweep * 60_000L),
                        countsChange, durabilityDrifts, random));
                written++;
            }
        }
        checkpoint(store);
        long after = dbBytes(db);
        store.close();

        long delta = after - before;
        double perCapture = written == 0 ? 0 : (double) delta / written;
        System.out.printf(Locale.ROOT, "%n--- %s ---%n", label);
        System.out.printf(Locale.ROOT, "  captures written : %d of %d attempted%n",
                written, PLAYERS * SWEEPS);
        System.out.printf(Locale.ROOT, "  db growth        : %s%n", human(delta));
        System.out.printf(Locale.ROOT, "  per capture      : %.0f B%n", perCapture);
        if (written == 0) {
            System.out.printf(Locale.ROOT, "  projection       : free at any player count%n");
            return;
        }
        // Projection: every player writing every sweep is the ceiling; real
        // servers only pay for players who actually changed something.
        double perDay = perCapture * PROJECT_PLAYERS * SWEEPS_PER_DAY;
        System.out.printf(Locale.ROOT,
                "  1000 players, all changing every sweep: %s/day, %s at %dd retention%n",
                human((long) perDay), human((long) (perDay * RETENTION_DAYS)), RETENTION_DAYS);
        for (int pct : new int[] {10, 25, 50}) {
            double d = perDay * pct / 100.0;
            System.out.printf(Locale.ROOT,
                    "  1000 players, %d%% changing per sweep : %s/day, %s at %dd retention%n",
                    pct, human((long) d), human((long) (d * RETENTION_DAYS)), RETENTION_DAYS);
        }
    }

    /**
     * One capture for a player. Plain stacks are drawn from a shared,
     * server-wide item population so interning can fold them; gear payloads
     * are per-player, and under {@code durabilityDrifts} they change every
     * sweep, which is what defeats interning.
     */
    private static PlayerSnapshot capture(UUID player, int playerIndex, int sweep, Instant at,
            boolean countsChange, boolean durabilityDrifts, Random random) {
        List<SnapshotSlot> slots = new ArrayList<>(OCCUPIED_SLOTS);
        for (int i = 0; i < OCCUPIED_SLOTS; i++) {
            boolean gear = i < GEAR_SLOTS;
            String payload;
            String material;
            if (gear) {
                material = "DIAMOND_CHESTPLATE";
                int damage = durabilityDrifts ? sweep : 0;
                payload = pad("gear:" + playerIndex + ":" + i + ":dmg" + damage, GEAR_PAYLOAD_BYTES);
            } else {
                material = "COBBLESTONE";
                int variety = (playerIndex * 7 + i) % PLAIN_ITEM_VARIETY;
                payload = pad("item:" + variety, PLAIN_PAYLOAD_BYTES);
            }
            // Counts move every sweep when the player is active; the payload
            // is normalized to amount 1 by the capture service, so the count
            // rides in the slot row, not the interned blob.
            int count = countsChange ? 1 + ((sweep * 3 + i) % 64) : 1 + (i % 64);
            slots.add(new SnapshotSlot(i, count,
                    new StoredItem(i, material, Base64.getEncoder()
                            .encodeToString(payload.getBytes(StandardCharsets.UTF_8)))));
        }
        long contentHash = random.nextLong();
        return new PlayerSnapshot(EventIds.newId(), player, "player" + playerIndex, at,
                PlayerSnapshot.CAUSE_SWEEP, contentHash, slots);
    }

    private static String pad(String seed, int bytes) {
        StringBuilder sb = new StringBuilder(seed);
        while (sb.length() < bytes) {
            sb.append('.');
        }
        return sb.toString();
    }

    /** Fold the WAL back into the main file so the size reflects everything. */
    private static void checkpoint(SqliteRecordStore store) {
        store.withWriteConnection(conn -> {
            try (var st = conn.createStatement()) {
                st.execute("PRAGMA wal_checkpoint(TRUNCATE)");
            }
            return null;
        });
    }

    private static long dbBytes(Path db) throws IOException {
        long total = 0;
        for (String suffix : new String[] {"", "-wal", "-shm"}) {
            Path p = Path.of(db + suffix);
            if (Files.exists(p)) {
                total += Files.size(p);
            }
        }
        return total;
    }

    private static String human(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] units = {"KiB", "MiB", "GiB", "TiB"};
        double value = bytes / 1024.0;
        int unit = 0;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
    }
}
