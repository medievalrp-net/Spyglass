package net.medievalrp.spyglass.plugin.storage;

import static org.assertj.core.api.Assumptions.assumeThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import net.medievalrp.spyglass.api.event.BlockBreakRecord;
import net.medievalrp.spyglass.api.event.BlockSnapshot;
import net.medievalrp.spyglass.api.event.EventRecord;
import net.medievalrp.spyglass.api.event.Origin;
import net.medievalrp.spyglass.api.event.Source;
import net.medievalrp.spyglass.api.query.Flag;
import net.medievalrp.spyglass.api.query.QueryPredicate;
import net.medievalrp.spyglass.api.query.QueryRequest;
import net.medievalrp.spyglass.api.query.Sort;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.api.util.EventIds;
import org.bukkit.Material;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * On-disk footprint + rollback-read throughput for {@link MariaDbRecordStore}
 * (#169), measured against a real InnoDB server.
 *
 * <p>Seeds the same realistic 2M block-edit cube the SQLite bench and
 * {@code compare.js} use (a {@code /fill stone} then {@code //replace stone
 * air}, so the disk numbers are apples-to-apples with the ~180 MiB
 * CoreProtect·MySQL footprint the issue targets), then reports:
 *
 * <ul>
 * <li>total on-disk bytes (data + index) for the schema, with a per-table
 * breakdown from {@code information_schema.TABLES} after {@code ANALYZE};</li>
 * <li>a full keyset rollback read over every row, in effects/sec - the lean
 * {@code streamRollbackEffects} path the engine drives;</li>
 * <li>a by-player scoped rollback read (the common operator action).</li>
 * </ul>
 *
 * <p>By default starts a throwaway Testcontainers MariaDB (Docker). Point it
 * at an external server with {@code -DSG_BENCH_MARIADB_HOST=...} (plus
 * {@code _PORT} / {@code _DB} / {@code _USER} / {@code _PASS}) to keep the
 * data around for inspection. Default 2,000,000 records; override with
 * {@code -DSG_BENCH_RECORDS=N}. Run with {@code ./gradlew :spyglass-core:mariadbBench}.
 */
@Tag("mariadb-bench")
class MariaDbBench {

    private static final int RECORDS = Integer.getInteger("SG_BENCH_RECORDS", 2_000_000);
    private static final int BATCH = 10_000;
    private static final int ROLLBACK_WINDOW = 4_000;
    private static final long CP_MYSQL_MIB = 180; // the figure to beat (README)

    private static final UUID WORLD = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID PLAYER = UUID.fromString("99999999-0000-0000-0000-000000001000");
    private static final int X0 = 10_000;
    private static final int Y0 = -32;
    private static final int Z0 = 10_000;
    private static final int SIDE = (int) Math.round(Math.cbrt(RECORDS));

    @Test
    void footprintAndRollbackAt2M() throws Exception {
        String extHost = System.getProperty("SG_BENCH_MARIADB_HOST");
        MariaDBContainer<?> container = null;
        String host;
        int port;
        String db;
        String user;
        String pw;
        if (extHost != null) {
            host = extHost;
            port = Integer.parseInt(System.getProperty("SG_BENCH_MARIADB_PORT", "3306"));
            db = System.getProperty("SG_BENCH_MARIADB_DB", "spyglass_bench");
            user = System.getProperty("SG_BENCH_MARIADB_USER", "root");
            pw = System.getProperty("SG_BENCH_MARIADB_PASS", "");
        } else {
            assumeThat(DockerClientFactory.instance().isDockerAvailable())
                    .as("docker not available")
                    .isTrue();
            container = new MariaDBContainer<>(DockerImageName.parse("mariadb:11"));
            container.start();
            host = container.getHost();
            port = container.getFirstMappedPort();
            db = container.getDatabaseName();
            user = container.getUsername();
            pw = container.getPassword();
        }

        MariaDbRecordStore store = new MariaDbRecordStore(host, port, db, user, pw, false, false);
        try {
            long t0 = System.nanoTime();
            seed(store);
            double seedSec = (System.nanoTime() - t0) / 1e9;

            System.out.printf(Locale.ROOT,
                    "%n=== MariaDB footprint @ %,d block records ===%n", RECORDS);
            System.out.printf(Locale.ROOT, "seed: %.1fs (%,.0f rec/s)%n", seedSec, RECORDS / seedSec);
            System.out.printf(Locale.ROOT, "rows in records table: %,d%n", store.count());

            double mib = reportDisk(host, port, db, user, pw);
            System.out.printf(Locale.ROOT,
                    "CoreProtect·MySQL target: ~%d MiB -> Spyglass·MariaDB is %s%n",
                    CP_MYSQL_MIB, mib < CP_MYSQL_MIB ? String.format(Locale.ROOT,
                            "SMALLER by %.0f%%", 100 * (CP_MYSQL_MIB - mib) / CP_MYSQL_MIB)
                            : "LARGER (over target!)");

            // Full rollback read over every row, lean path, in windows.
            AtomicLong effects = new AtomicLong();
            RecordStore.RollbackEffectSink sink = countingSink(effects);
            QueryRequest all = new QueryRequest(List.of(), Sort.NEWEST_FIRST, RECORDS,
                    java.util.EnumSet.noneOf(Flag.class), false);
            long r0 = System.nanoTime();
            QueryPage.Cursor cursor = null;
            do {
                cursor = store.streamRollbackEffects(all, cursor, ROLLBACK_WINDOW, true, sink);
            } while (cursor != null);
            double rbSec = (System.nanoTime() - r0) / 1e9;
            System.out.printf(Locale.ROOT,
                    "%nrollback read: %,d effects in %.2fs (%,.0f effects/s)%n",
                    effects.get(), rbSec, effects.get() / rbSec);

            // Scoped (single-player) rollback - the by-player index seek.
            AtomicLong scoped = new AtomicLong();
            RecordStore.RollbackEffectSink countSink = countingSink(scoped);
            long s0 = System.nanoTime();
            cursor = null;
            do {
                cursor = store.streamRollbackEffects(
                        new QueryRequest(List.of(new QueryPredicate.Eq("source.playerId", PLAYER)),
                                Sort.NEWEST_FIRST, RECORDS, java.util.EnumSet.noneOf(Flag.class), false),
                        cursor, ROLLBACK_WINDOW, true, countSink);
            } while (cursor != null);
            double scopedSec = (System.nanoTime() - s0) / 1e9;
            System.out.printf(Locale.ROOT,
                    "by-player rollback: %,d effects in %.3fs (%,.0f effects/s)%n",
                    scoped.get(), scopedSec, scoped.get() / scopedSec);
        } finally {
            store.close();
            if (container != null) {
                container.stop();
            }
        }
    }

    private double reportDisk(String host, int port, String db, String user, String pw) throws Exception {
        try (Connection conn = DriverManager.getConnection(
                "jdbc:mariadb://" + host + ":" + port + "/" + db, user, pw);
             Statement st = conn.createStatement()) {
            st.execute("ANALYZE TABLE records");
            st.execute("ANALYZE TABLE dict");
            st.execute("ANALYZE TABLE uuids");
            long total = 0;
            System.out.println("per-table bytes (information_schema, data+index):");
            try (ResultSet rs = st.executeQuery(
                    "SELECT table_name, data_length, index_length FROM information_schema.TABLES "
                            + "WHERE table_schema = '" + db + "' ORDER BY (data_length+index_length) DESC")) {
                while (rs.next()) {
                    long bytes = rs.getLong(2) + rs.getLong(3);
                    total += bytes;
                    System.out.printf(Locale.ROOT, " %-16s %8.1f MiB (data %.1f / index %.1f)%n",
                            rs.getString(1), bytes / (1024.0 * 1024.0),
                            rs.getLong(2) / (1024.0 * 1024.0), rs.getLong(3) / (1024.0 * 1024.0));
                }
            }
            double mib = total / (1024.0 * 1024.0);
            System.out.printf(Locale.ROOT, "TOTAL on disk (data + index): %.1f MiB (%.1f MiB / million)%n",
                    mib, mib / (RECORDS / 1_000_000.0));
            return mib;
        }
    }

    private void seed(MariaDbRecordStore store) {
        Instant base = Instant.now().minus(7, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS);
        List<EventRecord> batch = new ArrayList<>(BATCH);
        for (int i = 0; i < RECORDS; i++) {
            batch.add(record(i, base.plusMillis(i)));
            if (batch.size() == BATCH) {
                store.save(batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            store.save(batch);
        }
    }

    private EventRecord record(int i, Instant occurred) {
        int dx = i % SIDE;
        int dz = (i / SIDE) % SIDE;
        int dy = i / (SIDE * SIDE);
        BlockLocation loc = new BlockLocation(WORLD, "world", X0 + dx, Y0 + dy, Z0 + dz);
        return new BlockBreakRecord(EventIds.newId(), "break", occurred,
                occurred.plus(28, ChronoUnit.DAYS), Origin.player(),
                Source.player(PLAYER, "Builder"), loc, "world", "STONE",
                snap("minecraft:stone"), snap("minecraft:air"));
    }

    private static RecordStore.RollbackEffectSink countingSink(AtomicLong counter) {
        return new RecordStore.RollbackEffectSink() {
            @Override
            public void block(UUID world, int x, int y, int z, String data, String expected,
                              Instant occurred, UUID id) {
                counter.incrementAndGet();
            }

            @Override
            public void complex(net.medievalrp.spyglass.api.rollback.RollbackEffect effect,
                                Instant occurred, UUID id) {
                counter.incrementAndGet();
            }

            @Override
            public void skip(Instant occurred, UUID id) {
            }
        };
    }

    private static BlockSnapshot snap(String blockData) {
        return new BlockSnapshot(material(blockData), blockData,
                List.of(), List.of(), List.of(), List.of(), null);
    }

    private static Material material(String blockData) {
        String key = blockData.substring("minecraft:".length());
        int bracket = key.indexOf('[');
        if (bracket >= 0) {
            key = key.substring(0, bracket);
        }
        try {
            return Material.valueOf(key.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return Material.STONE;
        }
    }
}
