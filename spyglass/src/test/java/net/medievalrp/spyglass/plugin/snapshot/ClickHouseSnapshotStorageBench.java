package net.medievalrp.spyglass.plugin.snapshot;

import static org.assertj.core.api.Assumptions.assumeThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;
import net.medievalrp.spyglass.api.event.StoredItem;
import net.medievalrp.spyglass.api.util.EventIds;
import net.medievalrp.spyglass.plugin.storage.ClickHouseRecordStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.clickhouse.ClickHouseContainer;

/**
 * The ClickHouse counterpart to {@link PlayerSnapshotStorageBench}: what a
 * player-snapshot capture actually costs on the backend a large server runs.
 *
 * <p>Worth measuring separately because the two schemas are shaped very
 * differently. SQLite writes one row per occupied slot into
 * {@code player_snapshot_slots}; ClickHouse folds the whole capture into one
 * row, with the slots as three parallel arrays, in a compressed columnar
 * store. Same scenarios, same population, so the two runs are comparable.
 *
 * <p>Measured bytes are {@code bytes_on_disk} of the active parts of both
 * snapshot tables after {@code OPTIMIZE FINAL}, so compression and merges
 * are accounted for rather than measured mid-flight. Requires Docker; skips
 * cleanly without it. Run with
 * {@code ./gradlew :spyglass:snapshotStorageBench}.
 */
@Tag("snapshot-bench")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ClickHouseSnapshotStorageBench {

    private static final int PLAYERS = 200;
    private static final int SWEEPS = 60;
    private static final int OCCUPIED_SLOTS = 28;
    private static final int GEAR_SLOTS = 6;
    private static final int PLAIN_PAYLOAD_BYTES = 30;
    private static final int GEAR_PAYLOAD_BYTES = 250;
    private static final int PLAIN_ITEM_VARIETY = 300;

    private static final int PROJECT_PLAYERS = 1000;
    private static final int SWEEPS_PER_DAY = 24 * 60;
    private static final int RETENTION_DAYS = 30;

    private static final String DB = "spyglass_bench";

    private ClickHouseContainer container;
    private ClickHouseRecordStore store;

    @BeforeAll
    void setup() {
        assumeThat(DockerClientFactory.instance().isDockerAvailable())
                .as("docker not available")
                .isTrue();
        container = new ClickHouseContainer("clickhouse/clickhouse-server:24.8-alpine");
        container.start();
        store = new ClickHouseRecordStore(container.getHost(), container.getMappedPort(8123),
                DB, "event_records_bench", container.getUsername(), container.getPassword(), false);
    }

    @AfterAll
    void teardown() {
        if (store != null) {
            store.close();
        }
        if (container != null) {
            container.stop();
        }
    }

    @Test
    void footprintAtServerScale() {
        System.out.printf(Locale.ROOT,
                "%n=== ClickHouse player snapshot footprint (%d players x %d sweeps, %d slots) ===%n",
                PLAYERS, SWEEPS, OCCUPIED_SLOTS);
        run("stable kits (counts change, payloads repeat)", true, false, 0);
        run("durability drift (gear damaged every sweep)", true, true, 1);
    }

    /** {@code salt} keeps each scenario's players and payloads distinct so the
     *  two runs share no interned rows and their costs stay separable. */
    private void run(String label, boolean countsChange, boolean durabilityDrifts, int salt) {
        ClickHousePlayerSnapshotStore snapshots =
                new ClickHousePlayerSnapshotStore(store.client(), DB);
        Random random = new Random(42);

        List<UUID> players = new ArrayList<>(PLAYERS);
        for (int i = 0; i < PLAYERS; i++) {
            players.add(UUID.nameUUIDFromBytes(
                    ("bench-" + salt + "-" + i).getBytes(StandardCharsets.UTF_8)));
        }

        long baseMillis = 1_700_000_000_000L;
        for (int p = 0; p < PLAYERS; p++) {
            snapshots.save(capture(players.get(p), p, 0, Instant.ofEpochMilli(baseMillis),
                    false, false, random, salt));
        }
        long before = tableBytes();

        int written = 0;
        for (int sweep = 1; sweep <= SWEEPS; sweep++) {
            for (int p = 0; p < PLAYERS; p++) {
                snapshots.save(capture(players.get(p), p, sweep,
                        Instant.ofEpochMilli(baseMillis + sweep * 60_000L),
                        countsChange, durabilityDrifts, random, salt));
                written++;
            }
        }
        long after = tableBytes();

        long delta = after - before;
        double perCapture = (double) delta / written;
        System.out.printf(Locale.ROOT, "%n--- %s ---%n", label);
        System.out.printf(Locale.ROOT, "  captures written : %d%n", written);
        System.out.printf(Locale.ROOT, "  table growth     : %s%n", human(delta));
        System.out.printf(Locale.ROOT, "  per capture      : %.0f B%n", perCapture);
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

    /** Compressed on-disk size of both snapshot tables, merges settled. */
    private long tableBytes() {
        for (String table : new String[] {"player_snapshots", "snapshot_items"}) {
            store.client().queryAll("OPTIMIZE TABLE `" + DB + "`.`" + table + "` FINAL");
        }
        return store.client().queryAll(
                "SELECT sum(bytes_on_disk) AS b FROM system.parts "
                        + "WHERE active AND database = '" + DB + "' "
                        + "AND table IN ('player_snapshots','snapshot_items')")
                .get(0).getLong("b");
    }

    private static PlayerSnapshot capture(UUID player, int playerIndex, int sweep, Instant at,
            boolean countsChange, boolean durabilityDrifts, Random random, int salt) {
        List<SnapshotSlot> slots = new ArrayList<>(OCCUPIED_SLOTS);
        for (int i = 0; i < OCCUPIED_SLOTS; i++) {
            boolean gear = i < GEAR_SLOTS;
            String payload;
            String material;
            if (gear) {
                material = "DIAMOND_CHESTPLATE";
                int damage = durabilityDrifts ? sweep : 0;
                payload = pad("gear:" + salt + ":" + playerIndex + ":" + i + ":dmg" + damage,
                        GEAR_PAYLOAD_BYTES);
            } else {
                material = "COBBLESTONE";
                int variety = (playerIndex * 7 + i) % PLAIN_ITEM_VARIETY;
                payload = pad("item:" + salt + ":" + variety, PLAIN_PAYLOAD_BYTES);
            }
            int count = countsChange ? 1 + ((sweep * 3 + i) % 64) : 1 + (i % 64);
            slots.add(new SnapshotSlot(i, count,
                    new StoredItem(i, material, Base64.getEncoder()
                            .encodeToString(payload.getBytes(StandardCharsets.UTF_8)))));
        }
        return new PlayerSnapshot(EventIds.newId(), player, "player" + playerIndex, at,
                PlayerSnapshot.CAUSE_SWEEP, random.nextLong(), slots);
    }

    private static String pad(String seed, int bytes) {
        StringBuilder sb = new StringBuilder(seed);
        while (sb.length() < bytes) {
            sb.append('.');
        }
        return sb.toString();
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
