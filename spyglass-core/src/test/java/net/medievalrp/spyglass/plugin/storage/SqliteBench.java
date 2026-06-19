package net.medievalrp.spyglass.plugin.storage;

import java.nio.file.Files;
import java.nio.file.Path;
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
import net.medievalrp.spyglass.api.event.BlockPlaceRecord;
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

/**
 * On-disk footprint + rollback-read throughput for {@link SqliteRecordStore}
 * (#106), measured directly in-JVM — no server, no Docker, repeatable.
 *
 * <p>Seeds a realistic 2M block-edit stream (the same shape CoreProtect's
 * {@code co_block} holds, so the disk numbers are apples-to-apples with
 * the ~160 MiB CP·SQLite footprint the issue targets), checkpoints, and
 * reports:
 *
 * <ul>
 * <li>total on-disk bytes (db file + WAL), and per-table/index bytes via
 * {@code dbstat};</li>
 * <li>a full keyset rollback read over every row, in effects/sec — the
 * lean {@code streamRollbackEffects} path the engine drives.</li>
 * </ul>
 *
 * <p>Default 2,000,000 records; override with {@code -DSG_BENCH_RECORDS=N}.
 * Run with {@code ./gradlew :spyglass-core:sqliteBench}.
 */
@Tag("sqlite-bench")
class SqliteBench {

    private static final int RECORDS =
            Integer.getInteger("SG_BENCH_RECORDS", 2_000_000);
    private static final int BATCH = 10_000;
    private static final int ROLLBACK_WINDOW = 4_000;
    private static final long CP_SQLITE_MIB = 160; // the figure to beat (#106)

    // The dataset mirrors regression/bot/compare.js exactly — the scenario
    // CoreProtect's ~160 MiB was measured on — so the disk comparison is
    // apples-to-apples: a `/fill stone` then `//replace stone air` over a
    // contiguous cube. One player, one world, one block transition
    // (stone -> air), sequential coordinates. This is what a WorldEdit
    // rollback set actually looks like; a random scatter would be a
    // worst case neither store sees in the benchmark.
    private static final UUID WORLD = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID PLAYER = UUID.fromString("99999999-0000-0000-0000-000000001000");
    private static final int X0 = 10_000;
    private static final int Y0 = -32;
    private static final int Z0 = 10_000;
    private static final int SIDE = (int) Math.round(Math.cbrt(RECORDS));

    @Test
    void footprintAndRollbackAt2M() throws Exception {
        Path dir = Files.createTempDirectory("sg-sqlite-bench");
        Path db = dir.resolve("spyglass.db");
        SqliteRecordStore store = new SqliteRecordStore(db);
        try {
            long t0 = System.nanoTime();
            seed(store);
            store.checkpoint();
            double seedSec = (System.nanoTime() - t0) / 1e9;

            long total = fileBytes(db);
            System.out.printf(Locale.ROOT,
                    "%n=== SQLite footprint @ %,d block records ===%n", RECORDS);
            System.out.printf(Locale.ROOT, "seed: %.1fs (%,.0f rec/s)%n",
                    seedSec, RECORDS / seedSec);
            System.out.printf(Locale.ROOT, "rows in records table: %,d%n", store.count());
            printDbStat(db);
            double mib = total / (1024.0 * 1024.0);
            System.out.printf(Locale.ROOT, "TOTAL on disk (db + wal + shm): %.1f MiB (%.1f MiB / million)%n",
                    mib, mib / (RECORDS / 1_000_000.0));
            System.out.printf(Locale.ROOT, "CoreProtect·SQLite target: ~%d MiB -> Spyglass·SQLite is %s%n",
                    CP_SQLITE_MIB, mib < CP_SQLITE_MIB ? String.format(Locale.ROOT,
                            "SMALLER by %.0f%%", 100 * (CP_SQLITE_MIB - mib) / CP_SQLITE_MIB)
                            : "LARGER (over target!)");

            // Full rollback read over every row, lean path, in windows like
            // the engine. Counts emitted effects, times the whole sweep.
            AtomicLong effects = new AtomicLong();
            RecordStore.RollbackEffectSink sink = new RecordStore.RollbackEffectSink() {
                @Override
                public void block(UUID world, int x, int y, int z, String data,
                                  String expected, Instant occurred, UUID id) {
                    effects.incrementAndGet();
                }

                @Override
                public void complex(net.medievalrp.spyglass.api.rollback.RollbackEffect effect,
                                    Instant occurred, UUID id) {
                    effects.incrementAndGet();
                }

                @Override
                public void skip(Instant occurred, UUID id) {
                }
            };
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

            // A scoped (single-player) rollback — the common operator action —
            // to show the by-player index seek.
            UUID victim = PLAYER;
            AtomicLong scoped = new AtomicLong();
            RecordStore.RollbackEffectSink countSink = countingSink(scoped);
            long s0 = System.nanoTime();
            cursor = null;
            do {
                cursor = store.streamRollbackEffects(
                        new QueryRequest(List.of(new QueryPredicate.Eq("source.playerId", victim)),
                                Sort.NEWEST_FIRST, RECORDS, java.util.EnumSet.noneOf(Flag.class), false),
                        cursor, ROLLBACK_WINDOW, true, countSink);
            } while (cursor != null);
            double scopedSec = (System.nanoTime() - s0) / 1e9;
            System.out.printf(Locale.ROOT,
                    "by-player rollback: %,d effects in %.3fs (%,.0f effects/s)%n",
                    scoped.get(), scopedSec, scoped.get() / scopedSec);
        } finally {
            store.close();
            deleteTree(dir);
        }
    }

    private void seed(SqliteRecordStore store) {
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
        // Walk the cube in (x, then z, then y) order, the way a WorldEdit
        // //replace iterates a region — contiguous coordinates.
        int dx = i % SIDE;
        int dz = (i / SIDE) % SIDE;
        int dy = i / (SIDE * SIDE);
        BlockLocation loc = new BlockLocation(WORLD, "world", X0 + dx, Y0 + dy, Z0 + dz);
        // //replace stone air is logged as a break: stone -> air.
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

    // Derive a plausible Material enum constant from the block-data string.
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

    private static long fileBytes(Path db) {
        long total = 0;
        for (String suffix : new String[]{"", "-wal", "-shm"}) {
            Path p = Path.of(db + suffix);
            if (Files.exists(p)) {
                try {
                    total += Files.size(p);
                } catch (java.io.IOException ignored) {
                    // best effort
                }
            }
        }
        return total;
    }

    private static void printDbStat(Path db) {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT name, sum(pgsize) AS bytes FROM dbstat GROUP BY name ORDER BY bytes DESC")) {
            System.out.println("per-object bytes (dbstat):");
            while (rs.next()) {
                System.out.printf(Locale.ROOT, " %-16s %8.1f MiB%n",
                        rs.getString("name"), rs.getLong("bytes") / (1024.0 * 1024.0));
            }
        } catch (Exception ex) {
            System.out.println(" (dbstat unavailable: " + ex.getMessage() + ")");
        }
    }

    private static void deleteTree(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (java.io.IOException ignored) {
                    // best effort cleanup
                }
            });
        } catch (java.io.IOException ignored) {
            // best effort cleanup
        }
    }
}
