package net.medievalrp.spyglass.plugin.storage;

import static org.assertj.core.api.Assumptions.assumeThat;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.medievalrp.spyglass.api.event.BlockBreakRecord;
import net.medievalrp.spyglass.api.event.BlockPlaceRecord;
import net.medievalrp.spyglass.api.event.BlockSnapshot;
import net.medievalrp.spyglass.api.event.ChatRecord;
import net.medievalrp.spyglass.api.event.ContainerDepositRecord;
import net.medievalrp.spyglass.api.event.EntityDeathRecord;
import net.medievalrp.spyglass.api.event.EventRecord;
import net.medievalrp.spyglass.api.event.Origin;
import net.medievalrp.spyglass.api.event.Source;
import net.medievalrp.spyglass.api.event.StoredItem;
import net.medievalrp.spyglass.api.query.Flag;
import net.medievalrp.spyglass.api.query.QueryPredicate;
import net.medievalrp.spyglass.api.query.QueryRequest;
import net.medievalrp.spyglass.api.query.Sort;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.plugin.config.SpyglassConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.MongoDBContainer;

/**
 * Side-by-side benchmark of {@link MongoRecordStore} and
 * {@link ClickHouseRecordStore} against the same synthetic record
 * stream and the same query suite.
 *
 * <p>Defaults to 100 000 records; override with
 * {@code -DSG_BENCH_RECORDS=N}. Each query runs five times back to
 * back; the median wall time is reported so a single GC pause or
 * driver warmup doesn't dominate the headline number.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("ch-bench")
class ClickhouseVsMongoBench {

    private static final int DEFAULT_RECORDS = 100_000;
    private static final int BATCH_SIZE = 10_000;
    private static final int QUERY_REPEATS = 5;

    private static final UUID WORLD = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID WORLD_NETHER = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID[] PLAYERS = {
            UUID.fromString("11111111-1111-1111-1111-111111111111"),
            UUID.fromString("22222222-2222-2222-2222-222222222222"),
            UUID.fromString("33333333-3333-3333-3333-333333333333"),
            UUID.fromString("44444444-4444-4444-4444-444444444444"),
            UUID.fromString("55555555-5555-5555-5555-555555555555"),
            UUID.fromString("66666666-6666-6666-6666-666666666666"),
            UUID.fromString("77777777-7777-7777-7777-777777777777"),
            UUID.fromString("88888888-8888-8888-8888-888888888888")};
    private static final String[] PLAYER_NAMES = {
            "Alaric", "Brona", "Caedmon", "Dyrne", "Eira", "Faelan", "Gwilym", "Halric"};

    private static final String[] EVENT_MIX = pickWeighted();
    private static final long SEED = 0xC0FFEEL;

    private MongoDBContainer mongoContainer;
    private ClickHouseContainer clickHouseContainer;
    private MongoClient rawMongoClient;
    private MongoRecordStore mongoStore;
    private ClickHouseRecordStore clickHouseStore;
    private List<EventRecord> records;
    private Instant baseTime;

    @BeforeAll
    void setup() {
        assumeThat(DockerClientFactory.instance().isDockerAvailable())
                .as("docker not available")
                .isTrue();

        mongoContainer = new MongoDBContainer("mongo:7.0");
        clickHouseContainer = new ClickHouseContainer("clickhouse/clickhouse-server:24.8-alpine");

        Thread mongoThread = new Thread(mongoContainer::start, "bench-mongo-up");
        Thread chThread = new Thread(clickHouseContainer::start, "bench-ch-up");
        mongoThread.start();
        chThread.start();
        try {
            mongoThread.join();
            chThread.join();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(ie);
        }

        String mongoUri = mongoContainer.getReplicaSetUrl();
        rawMongoClient = MongoClients.create(mongoUri);
        SpyglassConfig.Database mongoConfig = new SpyglassConfig.Database(
                SpyglassConfig.Backend.MONGO,
                mongoUri,
                "SpyglassBench_" + System.nanoTime(),
                "EventRecords",
                stubClickHouseConfig());
        mongoStore = new MongoRecordStore(mongoConfig, new IndexManager());

        SpyglassConfig.ClickHouse chConfig = new SpyglassConfig.ClickHouse(
                clickHouseContainer.getHost(),
                clickHouseContainer.getMappedPort(8123),
                "sg_bench",
                "event_records_bench",
                clickHouseContainer.getUsername(),
                clickHouseContainer.getPassword(),
                false);
        clickHouseStore = new ClickHouseRecordStore(chConfig);

        int recordCount = Integer.parseInt(System.getProperty("SG_BENCH_RECORDS",
                String.valueOf(DEFAULT_RECORDS)));
        baseTime = Instant.now().minus(7, ChronoUnit.DAYS);
        records = generateRecords(recordCount, baseTime);
    }

    @AfterAll
    void teardown() {
        try {
            if (mongoStore != null) mongoStore.close();
        } catch (Exception ignored) {
        }
        try {
            if (clickHouseStore != null) clickHouseStore.close();
        } catch (Exception ignored) {
        }
        if (rawMongoClient != null) rawMongoClient.close();
        if (mongoContainer != null) mongoContainer.stop();
        if (clickHouseContainer != null) clickHouseContainer.stop();
    }

    @Test
    void runFullBenchmark() throws Exception {
        // Warmup: prime JIT, connection pool, CH part-merge cache.
        List<EventRecord> warmup = records.subList(0, Math.min(5_000, records.size()));
        insertInBatches(mongoStore, warmup);
        insertInBatches(clickHouseStore, warmup);
        QueryRequest warmupQuery = new QueryRequest(
                List.of(new QueryPredicate.Eq("event", "break")),
                Sort.NEWEST_FIRST, 100, EnumSet.noneOf(Flag.class), false);
        mongoStore.query(warmupQuery);
        clickHouseStore.query(warmupQuery);

        // Reset to clean tables before the timed phase.
        truncateMongo();
        truncateClickHouse();

        long mongoInsertNanos = timed(() -> insertInBatches(mongoStore, records));
        long clickHouseInsertNanos = timed(() -> insertInBatches(clickHouseStore, records));

        clickHouseStore.optimize();

        long mongoCount = mongoCount();
        long clickHouseCount = clickHouseStore.count();
        long mongoBytes = mongoStorageBytes();
        long chCompressed = clickHouseStore.compressedBytes();
        long chUncompressed = clickHouseStore.uncompressedBytes();

        Instant tMid = baseTime.plus(3, ChronoUnit.DAYS);
        Instant tEnd = baseTime.plus(7, ChronoUnit.DAYS);

        QueryRequest byPlayer = new QueryRequest(
                List.of(new QueryPredicate.Eq("source.playerId", PLAYERS[0])),
                Sort.NEWEST_FIRST, 100, EnumSet.noneOf(Flag.class), false);

        QueryRequest byEventAndTime = new QueryRequest(
                List.of(
                        new QueryPredicate.Eq("event", "break"),
                        new QueryPredicate.Range("occurred", tMid, tEnd)),
                Sort.NEWEST_FIRST, 1_000, EnumSet.noneOf(Flag.class), false);

        QueryRequest spatial = new QueryRequest(
                List.of(
                        new QueryPredicate.Eq("location.worldId", WORLD),
                        new QueryPredicate.Range("location.x", -100, 100),
                        new QueryPredicate.Range("location.z", -100, 100)),
                Sort.NEWEST_FIRST, 1_000, EnumSet.noneOf(Flag.class), false);

        QueryRequest scanRecent = new QueryRequest(
                List.of(),
                Sort.NEWEST_FIRST, 1_000, EnumSet.noneOf(Flag.class), false);

        QueryRequest playerEventTime = new QueryRequest(
                List.of(
                        new QueryPredicate.Eq("source.playerId", PLAYERS[1]),
                        new QueryPredicate.In("event", List.of("break", "place")),
                        new QueryPredicate.Range("occurred", baseTime, tEnd)),
                Sort.NEWEST_FIRST, 500, EnumSet.noneOf(Flag.class), false);

        BenchResult byPlayerResult = compareQuery("by player", byPlayer);
        BenchResult byEventTimeResult = compareQuery("by event + time range", byEventAndTime);
        BenchResult spatialResult = compareQuery("by spatial range", spatial);
        BenchResult scanRecentResult = compareQuery("scan newest 1000", scanRecent);
        BenchResult playerEventTimeResult = compareQuery("player + event-IN + time range", playerEventTime);

        StringBuilder report = new StringBuilder();
        report.append("\n");
        report.append("================ ClickHouse vs MongoDB benchmark ================\n");
        report.append(String.format(Locale.ROOT, "records inserted : %,d (Mongo=%,d, ClickHouse=%,d)%n",
                records.size(), mongoCount, clickHouseCount));
        report.append("\n--- INSERT ---\n");
        reportThroughput(report, "Mongo ", records.size(), mongoInsertNanos);
        reportThroughput(report, "ClickHouse ", records.size(), clickHouseInsertNanos);
        report.append("\n--- STORAGE ---\n");
        report.append(String.format(Locale.ROOT, "Mongo data + indexes : %s%n", humanBytes(mongoBytes)));
        report.append(String.format(Locale.ROOT, "ClickHouse on-disk : %s (uncompressed %s, ratio %.2fx)%n",
                humanBytes(chCompressed),
                humanBytes(chUncompressed),
                chCompressed > 0 ? (double) chUncompressed / chCompressed : 0.0));
        report.append("\n--- QUERY (median of " + QUERY_REPEATS + " runs) ---\n");
        report.append(String.format(Locale.ROOT, "%-38s %12s %12s %10s%n",
                "scenario", "Mongo (ms)", "CH (ms)", "speedup"));
        for (BenchResult r : List.of(byPlayerResult, byEventTimeResult, spatialResult,
                scanRecentResult, playerEventTimeResult)) {
            report.append(String.format(Locale.ROOT, "%-38s %12.2f %12.2f %9.2fx (Mongo:%d / CH:%d rows)%n",
                    r.label,
                    r.mongoMs,
                    r.clickHouseMs,
                    r.mongoMs / Math.max(r.clickHouseMs, 0.0001),
                    r.mongoRows,
                    r.clickHouseRows));
        }
        report.append("==================================================================\n");
        System.out.println(report);
    }

    private void reportThroughput(StringBuilder out, String label, int n, long nanos) {
        double ms = nanos / 1_000_000.0;
        double recPerSec = n / (nanos / 1e9);
        out.append(String.format(Locale.ROOT, "%s : %,d records in %,.0f ms = %,.0f rec/s%n",
                label, n, ms, recPerSec));
    }

    private BenchResult compareQuery(String label, QueryRequest request) {
        // querySummary mirrors what `/sg search` actually runs in
        // production: the search renderer never reads the heavy
        // BlockSnapshot / StoredItem fields, so both backends drop
        // them at projection time. Comparing query() (which also
        // fetches the heavy BSON blobs) would measure a workload no
        // user actually hits and inflate CH's wire-transfer cost on
        // the player-lookup path because the by_player projection
        // stores those columns too.
        long mongoMedian = medianNanos(QUERY_REPEATS, () -> mongoStore.querySummary(request));
        long chMedian = medianNanos(QUERY_REPEATS, () -> clickHouseStore.querySummary(request));
        int mongoRows = mongoStore.querySummary(request).records().size();
        int chRows = clickHouseStore.querySummary(request).records().size();
        return new BenchResult(label, mongoMedian / 1_000_000.0, chMedian / 1_000_000.0, mongoRows, chRows);
    }

    private long medianNanos(int runs, ThrowingRunnable<RuntimeException> r) {
        long[] samples = new long[runs];
        for (int i = 0; i < runs; i++) {
            long start = System.nanoTime();
            r.run();
            samples[i] = System.nanoTime() - start;
        }
        java.util.Arrays.sort(samples);
        return samples[runs / 2];
    }

    private long timed(ThrowingRunnable<Exception> r) throws Exception {
        long start = System.nanoTime();
        r.run();
        return System.nanoTime() - start;
    }

    private void insertInBatches(RecordStore store, List<EventRecord> all) {
        for (int i = 0; i < all.size(); i += BATCH_SIZE) {
            int end = Math.min(i + BATCH_SIZE, all.size());
            store.save(all.subList(i, end));
        }
    }

    private void truncateMongo() {
        mongoStore.database().getCollection("EventRecords").drop();
        new IndexManager().ensureRecordIndexes(
                mongoStore.database().getCollection("EventRecords", org.bson.BsonDocument.class));
    }

    private void truncateClickHouse() throws Exception {
        clickHouseStore.client().execute("TRUNCATE TABLE `sg_bench`.`event_records_bench`")
                .get(30, java.util.concurrent.TimeUnit.SECONDS).close();
    }

    private long mongoCount() {
        return mongoStore.database().getCollection("EventRecords").countDocuments();
    }

    private long mongoStorageBytes() {
        var stats = mongoStore.database().runCommand(new org.bson.Document("collStats", "EventRecords"));
        long size = ((Number) stats.getOrDefault("size", 0L)).longValue();
        long indexSize = ((Number) stats.getOrDefault("totalIndexSize", 0L)).longValue();
        return size + indexSize;
    }

    private static SpyglassConfig.ClickHouse stubClickHouseConfig() {
        return new SpyglassConfig.ClickHouse(
                "localhost", 8123, "x", "x", "default", "", false);
    }

    private static String[] pickWeighted() {
        List<String> bag = new ArrayList<>();
        for (int i = 0; i < 30; i++) bag.add("break");
        for (int i = 0; i < 20; i++) bag.add("place");
        for (int i = 0; i < 12; i++) bag.add("deposit");
        for (int i = 0; i < 12; i++) bag.add("withdraw");
        for (int i = 0; i < 8; i++) bag.add("say");
        for (int i = 0; i < 8; i++) bag.add("death");
        for (int i = 0; i < 5; i++) bag.add("join");
        for (int i = 0; i < 5; i++) bag.add("quit");
        return bag.toArray(new String[0]);
    }

    private List<EventRecord> generateRecords(int count, Instant start) {
        java.util.Random rnd = new java.util.Random(SEED);
        List<EventRecord> all = new ArrayList<>(count);

        BlockSnapshot stone = new BlockSnapshot(
                org.bukkit.Material.STONE, "minecraft:stone",
                List.of(), List.of(), List.of(), List.of(), null);
        BlockSnapshot air = new BlockSnapshot(
                org.bukkit.Material.AIR, "minecraft:air",
                List.of(), List.of(), List.of(), List.of(), null);
        BlockSnapshot chest = new BlockSnapshot(
                org.bukkit.Material.CHEST, "minecraft:chest",
                List.of(), List.of(), List.of(), List.of(), null);
        StoredItem diamond = new StoredItem(3, "DIAMOND", null);

        long spanSeconds = TimeUnit.DAYS.toSeconds(7);

        for (int i = 0; i < count; i++) {
            String event = EVENT_MIX[rnd.nextInt(EVENT_MIX.length)];
            int playerIdx = rnd.nextInt(PLAYERS.length);
            UUID world = rnd.nextInt(10) == 0 ? WORLD_NETHER : WORLD;
            int x = rnd.nextInt(2000) - 1000;
            int z = rnd.nextInt(2000) - 1000;
            int y = 60 + rnd.nextInt(40);
            BlockLocation loc = new BlockLocation(world, world.equals(WORLD) ? "world" : "world_nether",
                    x, y, z);
            Instant occurred = start.plusSeconds(rnd.nextLong(spanSeconds));
            Instant expiresAt = occurred.plus(28, ChronoUnit.DAYS);
            Origin origin = Origin.player();
            Source source = Source.player(PLAYERS[playerIdx], PLAYER_NAMES[playerIdx]);
            UUID id = new UUID(rnd.nextLong(), rnd.nextLong());

            EventRecord record = switch (event) {
                case "break" -> new BlockBreakRecord(id, "break", occurred, expiresAt,
                        origin, source, loc, "STONE", stone, air);
                case "place" -> new BlockPlaceRecord(id, "place", occurred, expiresAt,
                        origin, source, loc, "STONE", air, stone);
                case "deposit" -> new ContainerDepositRecord(id, "deposit", occurred, expiresAt,
                        origin, source, loc, "DIAMOND", "CHEST", 3, 1, null, diamond);
                case "withdraw" -> new net.medievalrp.spyglass.api.event.ContainerWithdrawRecord(
                        id, "withdraw", occurred, expiresAt,
                        origin, source, loc, "DIAMOND", "CHEST", 3, 1, diamond, null);
                case "say" -> new ChatRecord(id, "say", occurred, expiresAt,
                        origin, source, loc, PLAYER_NAMES[playerIdx],
                        "msg-" + i, List.of());
                case "death" -> new EntityDeathRecord(id, "death", occurred, expiresAt,
                        origin, source, loc, "ZOMBIE", "ZOMBIE",
                        UUID.randomUUID(), "player", "ENTITY_ATTACK", null);
                case "join" -> new net.medievalrp.spyglass.api.event.JoinRecord(
                        id, "join", occurred, expiresAt,
                        origin, source, loc, PLAYER_NAMES[playerIdx],
                        "127.0.0.1");
                case "quit" -> new net.medievalrp.spyglass.api.event.QuitRecord(
                        id, "quit", occurred, expiresAt,
                        origin, source, loc, PLAYER_NAMES[playerIdx]);
                default -> new BlockBreakRecord(id, "break", occurred, expiresAt,
                        origin, source, loc, "STONE", stone, air);
            };
            if (rnd.nextInt(50) == 0 && record instanceof BlockPlaceRecord place) {
                record = new BlockPlaceRecord(place.id(), place.event(),
                        place.occurred(), place.expiresAt(),
                        place.origin(), place.source(), place.location(),
                        "CHEST", air, chest);
            }
            all.add(record);
        }
        return all;
    }

    private static String humanBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.ROOT, "%.1f KiB", kb);
        double mb = kb / 1024;
        if (mb < 1024) return String.format(Locale.ROOT, "%.2f MiB", mb);
        return String.format(Locale.ROOT, "%.2f GiB", mb / 1024);
    }

    @FunctionalInterface
    private interface ThrowingRunnable<X extends Exception> {
        void run() throws X;
    }

    private record BenchResult(
            String label,
            double mongoMs,
            double clickHouseMs,
            int mongoRows,
            int clickHouseRows) {
    }
}
