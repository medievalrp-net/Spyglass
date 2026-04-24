package net.medievalrp.spyglass.plugin.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.BulkWriteOptions;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.InsertOneModel;
import com.mongodb.client.model.WriteModel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.medievalrp.spyglass.api.event.BlockBreakRecord;
import net.medievalrp.spyglass.api.event.BlockSnapshot;
import net.medievalrp.spyglass.api.event.EventRecord;
import net.medievalrp.spyglass.api.event.Origin;
import net.medievalrp.spyglass.api.event.Source;
import net.medievalrp.spyglass.api.query.QueryRequest;
import net.medievalrp.spyglass.api.query.QueryResult;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.api.util.Duration;
import net.medievalrp.spyglass.plugin.config.SpyglassConfig;
import net.medievalrp.spyglass.plugin.storage.IndexManager;
import net.medievalrp.spyglass.plugin.storage.MongoRecordStore;
import net.medievalrp.spyglass.plugin.storage.RecordStore;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MongoDBContainer;

/**
 * Ingest throughput benchmark: v1 v2 vs synthetic v1-equivalent.
 *
 * <p>Runs under the {@code bench} JUnit tag. The default {@code test}
 * task excludes {@code bench}; run this explicitly with {@code ./gradlew
 * :spyglass:ingestBench}. Gated on Docker availability; skipped
 * gracefully otherwise.
 *
 * <p><b>Why an in-process bench?</b> The existing {@code regression/bench.py}
 * measures end-to-end query latency over RCON with a live server. For
 * ingest we care about the write pipeline itself — queue semantics, drop
 * behavior under backpressure, save-path cost — not the network hop. An
 * in-process bench pointing at a testcontainers Mongo gives deterministic
 * numbers that isolate the plugin's ingest machinery from server-runtime
 * noise.
 *
 * <p><b>Why compare to v1?</b> Spyglass replaces v1 in
 * production. The pipelines share one hard invariant but have very
 * different shapes under it:
 *
 * <ul>
 * <li><b>v1</b>: Unbounded {@link LinkedBlockingDeque} + scheduled
 * batch-drain task that flushes the whole queue per tick, synchronous
 * {@code bulkWrite} via {@code MongoRecordHandler.write}. No retry,
 * no atomic counters. Intake is lossless; OOM is the only failure
 * mode under sustained Mongo unavailability. (See {@code
 * EntryQueueRunner} + {@code MongoStorageHandler} in the v1 core.)</li>
 * <li><b>v2</b>: Unbounded {@link LinkedBlockingDeque} + virtual-thread
 * continuous drain + per-batch save with exponential-backoff retry
 * + atomic {@code drained}/{@code dropped} counters. Intake is
 * lossless (matches v1); {@code queue-capacity} is a soft
 * <i>warn threshold</i> for queue depth, not a drop ceiling. Retry
 * makes transient Mongo hiccups recoverable; continuous drain
 * keeps latency flat instead of bursty. Shutdown flush with
 * deadline-bounded retry is the only path that can drop, and only
 * if Mongo stays unreachable for the whole flush window. (See
 * {@link AsyncRecorder}.)</li>
 * </ul>
 *
 * <p>The v1-equivalent harness here reconstructs v1's algorithm in this
 * JVM: unbounded deque, scheduled drain every 50ms (matches Bukkit 20Hz),
 * bulkWrite with v1-shape documents. It writes to a sibling Mongo database
 * so both pipelines contend against the same Mongo instance — the only
 * difference is the pipeline shape and doc schema.
 *
 * <p><b>Metrics</b>: offered, saved, dropped, wall-clock drain time,
 * effective events/sec, queue high-water, per-event ingest latency
 * (submit nanoTime &rarr; Mongo bulkWrite return nanoTime) distribution
 * (p50/p90/p95/p99/max). A JSON report is written to {@code
 * build/reports/ingest-bench.json} for trend tracking.
 *
 * <p>Three scenarios:
 * <ol>
 * <li>{@code sustained_*_per_sec} — N seconds at a controlled producer
 * rate. Measures steady-state latency + queue depth.</li>
 * <li>{@code burst_*} — 8 threads push N records each as fast as
 * possible. Measures saturated throughput + per-event latency
 * under contention.</li>
 * <li>{@code overload_*} — Push a large batch well past v2's warn
 * threshold. Measures queue growth + retrospective drain
 * throughput; both pipelines must save 100% of records with zero
 * drops.</li>
 * </ol>
 */
@Tag("bench")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IngestThroughputBench {

    private static final Logger BENCH_LOG = Logger.getLogger(IngestThroughputBench.class.getName());

    // Controls — env or -D overridable so CI can retarget without recompile.
    private static final long SUSTAINED_TARGET_RATE = envLong("SG_BENCH_SUSTAINED_RATE", 10_000L);
    private static final long SUSTAINED_DURATION_SEC = envLong("SG_BENCH_SUSTAINED_SEC", 15L);
    private static final int BURST_THREADS = (int) envLong("SG_BENCH_BURST_THREADS", 8L);
    private static final int BURST_PER_THREAD = (int) envLong("SG_BENCH_BURST_PER", 25_000L);
    private static final int OVERLOAD_RECORDS = (int) envLong("SG_BENCH_OVERLOAD", 200_000L);
    // Warn threshold for v2's AsyncRecorder. Deliberately set an order of
    // magnitude below the overload batch so the warn path fires — but the
    // queue is unbounded, so no drops happen. Env key keeps the legacy
    // _CAP suffix for CI back-compat; semantic is warn threshold.
    private static final int V2_WARN_THRESHOLD = (int) envLong("SG_BENCH_V2_CAP", 10_000L);

    private MongoDBContainer container;
    private MongoClient mongoClient;
    private String mongoUri;
    private final List<Map<String, Object>> scenarioReports = new ArrayList<>();

    @BeforeAll
    void setup() {
        assumeThat(DockerClientFactory.instance().isDockerAvailable())
                .as("docker not available; skipping ingest bench")
                .isTrue();
        container = new MongoDBContainer("mongo:7.0");
        container.start();
        mongoUri = container.getReplicaSetUrl();
        mongoClient = MongoClients.create(mongoUri);
        BENCH_LOG.setLevel(Level.INFO);
        BENCH_LOG.info(() -> "Mongo: " + mongoUri);
        BENCH_LOG.info(() -> "Controls: sustained=" + SUSTAINED_TARGET_RATE + "/s × "
                + SUSTAINED_DURATION_SEC + "s, burst=" + BURST_THREADS + "×" + BURST_PER_THREAD
                + ", overload=" + OVERLOAD_RECORDS + " (v2 warn=" + V2_WARN_THRESHOLD + ")");
    }

    @AfterAll
    void teardown() {
        if (mongoClient != null) mongoClient.close();
        if (container != null) container.stop();
        writeReports();
    }

    // -------- scenarios ----------------------------------------------------

    @Test
    void sustained_target_rate() throws Exception {
        long totalOps = SUSTAINED_TARGET_RATE * SUSTAINED_DURATION_SEC;
        Harness v2 = Harness.v2(mongoUri, V2_WARN_THRESHOLD);
        ScenarioResult v2Result = runSustained(v2, SUSTAINED_TARGET_RATE, totalOps);
        Harness v1 = Harness.v1(mongoClient);
        ScenarioResult v1Result = runSustained(v1, SUSTAINED_TARGET_RATE, totalOps);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("scenario", "sustained_" + SUSTAINED_TARGET_RATE + "_per_sec");
        report.put("target_rate_per_sec", SUSTAINED_TARGET_RATE);
        report.put("duration_sec", SUSTAINED_DURATION_SEC);
        report.put("total_ops", totalOps);
        report.put("v2", v2Result.toMap());
        report.put("v1", v1Result.toMap());
        scenarioReports.add(report);

        printTable("SUSTAINED " + SUSTAINED_TARGET_RATE + " ev/s × "
                + SUSTAINED_DURATION_SEC + "s (target " + totalOps + " ops)",
                v2Result, v1Result);

        assertThat(v2Result.observedRatePerSec())
                .as("v2 must achieve >=85%% of target rate under steady-state load")
                .isGreaterThan(SUSTAINED_TARGET_RATE * 0.85);
        assertThat(v2Result.dropped)
                .as("no-drop invariant: v2 must never drop with a healthy store")
                .isZero();
    }

    @Test
    void burst_max_throughput() throws Exception {
        Harness v2 = Harness.v2(mongoUri, V2_WARN_THRESHOLD);
        ScenarioResult v2Result = runBurst(v2, BURST_THREADS, BURST_PER_THREAD);
        Harness v1 = Harness.v1(mongoClient);
        ScenarioResult v1Result = runBurst(v1, BURST_THREADS, BURST_PER_THREAD);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("scenario", "burst_" + BURST_THREADS + "x" + BURST_PER_THREAD);
        report.put("threads", BURST_THREADS);
        report.put("per_thread", BURST_PER_THREAD);
        report.put("v2", v2Result.toMap());
        report.put("v1", v1Result.toMap());
        scenarioReports.add(report);

        printTable("BURST " + BURST_THREADS + "×" + BURST_PER_THREAD
                + " (" + (long) BURST_THREADS * BURST_PER_THREAD + " records)",
                v2Result, v1Result);

        long offered = (long) BURST_THREADS * BURST_PER_THREAD;
        assertThat(v2Result.dropped)
                .as("no-drop invariant: v2 must not drop under burst load")
                .isZero();
        assertThat(v2Result.saved)
                .as("v2 must save every record offered in a burst")
                .isEqualTo(offered);
        assertThat(v1Result.saved)
                .as("v1 is unbounded and must save every record")
                .isEqualTo(offered);
    }

    @Test
    void overload_neither_pipeline_drops() throws Exception {
        // The previous version of this test verified that v2's bounded
        // queue dropped under overload while v1 did not. That asymmetry
        // was a regression vs v1's no-drop contract and has been
        // eliminated — v2's queue is now unbounded (see AsyncRecorder's
        // no-drop Javadoc). The scenario is still useful because it
        // exercises the warn-threshold path and measures retrospective
        // drain throughput once the producer stops. Both pipelines must
        // save 100% of records with zero drops.
        int perThread = OVERLOAD_RECORDS / 8;
        Harness v2 = Harness.v2(mongoUri, V2_WARN_THRESHOLD);
        ScenarioResult v2Result = runBurst(v2, 8, perThread);
        Harness v1 = Harness.v1(mongoClient);
        ScenarioResult v1Result = runBurst(v1, 8, perThread);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("scenario", "overload_" + OVERLOAD_RECORDS + "_warn_" + V2_WARN_THRESHOLD);
        report.put("total_offered", (long) 8 * perThread);
        report.put("v2_warn_threshold", V2_WARN_THRESHOLD);
        report.put("v2", v2Result.toMap());
        report.put("v1", v1Result.toMap());
        scenarioReports.add(report);

        printTable("OVERLOAD " + 8 * perThread + " offered, v2 warn=" + V2_WARN_THRESHOLD,
                v2Result, v1Result);

        long offered = (long) 8 * perThread;
        assertThat(v2Result.dropped)
                .as("no-drop invariant: v2 must not drop even when producer bursts past warn threshold")
                .isZero();
        assertThat(v2Result.saved)
                .as("v2 must save every offered record (unbounded queue, healthy store)")
                .isEqualTo(offered);
        assertThat(v1Result.dropped)
                .as("v1 unbounded pipeline must not drop")
                .isZero();
        assertThat(v1Result.saved)
                .as("v1 must save every offered record")
                .isEqualTo(offered);
    }

    // -------- drivers ------------------------------------------------------

    /**
     * Open-loop, rate-controlled producer. Paces submissions at {@code
     * ratePerSec} regardless of downstream progress. For v2 the {@code
     * record()} call is non-blocking (offer + atomic increment); for v1
     * the {@code queue.add()} call is non-blocking on an unbounded deque.
     * So the producer rate is essentially the programmed rate, and any
     * backpressure shows up as queue depth + dropped count downstream.
     */
    private ScenarioResult runSustained(Harness h, long ratePerSec, long totalOps)
            throws Exception {
        long intervalNanos = TimeUnit.SECONDS.toNanos(1) / ratePerSec;
        long warmupOps = Math.min(ratePerSec, totalOps / 10); // 10% or 1s

        AtomicLong queueHighWater = new AtomicLong();
        ScheduledExecutorService sampler = Executors.newSingleThreadScheduledExecutor(
                r -> {
                    Thread t = new Thread(r, "bench-hw-sampler-" + h.label);
                    t.setDaemon(true);
                    return t;
                });
        sampler.scheduleAtFixedRate(
                () -> updateMax(queueHighWater, h.queueDepthFn.getAsLong()),
                0, 10, TimeUnit.MILLISECONDS);

        long startNanos = System.nanoTime();
        long nextOfferNanos = startNanos;
        for (long i = 0; i < totalOps; i++) {
            long now;
            while ((now = System.nanoTime()) < nextOfferNanos) {
                java.util.concurrent.locks.LockSupport.parkNanos(
                        Math.min(50_000L, nextOfferNanos - now));
            }
            h.submitFn.accept(randomEvent(i), i >= warmupOps);
            nextOfferNanos += intervalNanos;
        }
        long submitDoneNanos = System.nanoTime();

        h.flushFn.run();
        long flushDoneNanos = System.nanoTime();
        sampler.shutdownNow();

        ScenarioResult result = new ScenarioResult();
        result.label = h.label;
        result.offered = totalOps;
        result.saved = h.savedFn.getAsLong();
        result.dropped = h.droppedFn.getAsLong();
        result.producerElapsedMs = nanosToMillis(submitDoneNanos - startNanos);
        result.totalElapsedMs = nanosToMillis(flushDoneNanos - startNanos);
        result.queueHighWater = queueHighWater.get();
        result.latencyNanos = h.latencyNanos;
        return result;
    }

    private ScenarioResult runBurst(Harness h, int threads, int perThread) throws Exception {
        long totalOps = (long) threads * perThread;
        AtomicLong queueHighWater = new AtomicLong();
        ScheduledExecutorService sampler = Executors.newSingleThreadScheduledExecutor(
                r -> {
                    Thread t = new Thread(r, "bench-hw-sampler-" + h.label);
                    t.setDaemon(true);
                    return t;
                });
        sampler.scheduleAtFixedRate(
                () -> updateMax(queueHighWater, h.queueDepthFn.getAsLong()),
                0, 10, TimeUnit.MILLISECONDS);

        CountDownLatch start = new CountDownLatch(1);
        List<Thread> producers = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final int threadId = t;
            Thread producer = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        long seed = (long) threadId * perThread + i;
                        h.submitFn.accept(randomEvent(seed), true);
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }, "bench-producer-" + h.label + "-" + t);
            producers.add(producer);
            producer.start();
        }
        long startNanos = System.nanoTime();
        start.countDown();
        for (Thread p : producers) {
            p.join();
        }
        long submitDoneNanos = System.nanoTime();

        h.flushFn.run();
        long flushDoneNanos = System.nanoTime();
        sampler.shutdownNow();

        ScenarioResult result = new ScenarioResult();
        result.label = h.label;
        result.offered = totalOps;
        result.saved = h.savedFn.getAsLong();
        result.dropped = h.droppedFn.getAsLong();
        result.producerElapsedMs = nanosToMillis(submitDoneNanos - startNanos);
        result.totalElapsedMs = nanosToMillis(flushDoneNanos - startNanos);
        result.queueHighWater = queueHighWater.get();
        result.latencyNanos = h.latencyNanos;
        return result;
    }

    // -------- reports ------------------------------------------------------

    private static void printTable(String title, ScenarioResult v2, ScenarioResult v1) {
        System.out.println();
        System.out.println("═══ " + title + " ═══");
        System.out.printf(Locale.ROOT,
                " %-5s %10s %10s %10s %10s %10s %10s %10s %10s %10s%n",
                "ver", "offered", "saved", "dropped", "prod(ms)", "total(ms)",
                "eff r/s", "p50(µs)", "p95(µs)", "p99(µs)");
        printRow(v2);
        printRow(v1);
        System.out.printf(Locale.ROOT, " queue high-water: v2=%d v1=%d%n",
                v2.queueHighWater, v1.queueHighWater);
        System.out.println();
    }

    private static void printRow(ScenarioResult r) {
        long[] lat = toSortedArray(r.latencyNanos);
        long p50 = percentile(lat, 0.50), p95 = percentile(lat, 0.95), p99 = percentile(lat, 0.99);
        System.out.printf(Locale.ROOT,
                " %-5s %10d %10d %10d %10d %10d %10.0f %10d %10d %10d%n",
                r.label, r.offered, r.saved, r.dropped,
                r.producerElapsedMs, r.totalElapsedMs, r.observedRatePerSec(),
                p50 / 1_000, p95 / 1_000, p99 / 1_000);
    }

    private void writeReports() {
        if (scenarioReports.isEmpty()) {
            return;
        }
        StringBuilder json = new StringBuilder();
        json.append("{\n \"scenarios\": [\n");
        for (int i = 0; i < scenarioReports.size(); i++) {
            json.append(" ").append(toJson(scenarioReports.get(i)));
            if (i < scenarioReports.size() - 1) json.append(",");
            json.append("\n");
        }
        json.append(" ]\n}\n");

        try {
            Path jsonOut = Paths.get("build/reports/ingest-bench.json");
            Files.createDirectories(jsonOut.getParent());
            Files.writeString(jsonOut, json.toString());
            BENCH_LOG.info(() -> "Wrote " + jsonOut.toAbsolutePath());
        } catch (Exception ex) {
            BENCH_LOG.warning("Failed to write ingest-bench.json: " + ex.getMessage());
        }
    }

    private static String toJson(Object v) {
        if (v == null) return "null";
        if (v instanceof Number || v instanceof Boolean) return v.toString();
        if (v instanceof Map<?, ?> m) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(",");
                first = false;
                sb.append("\"").append(escape(e.getKey().toString())).append("\":").append(toJson(e.getValue()));
            }
            return sb.append("}").toString();
        }
        if (v instanceof List<?> l) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < l.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append(toJson(l.get(i)));
            }
            return sb.append("]").toString();
        }
        return "\"" + escape(v.toString()) + "\"";
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // -------- helpers ------------------------------------------------------

    /** Per-seed reproducible synthetic BlockBreak record. */
    private static EventRecord randomEvent(long seed) {
        Random rng = new Random(seed);
        Instant now = Instant.now();
        UUID id = new UUID(0xBE00_BE00_BE00_BE00L ^ seed, seed);
        UUID worldId = new UUID(0x7777777777777777L, 0x7777777777777777L);
        UUID playerId = new UUID(0x1000L + (seed & 0xF), 0x2000L);
        BlockSnapshot original = new BlockSnapshot(
                org.bukkit.Material.STONE, "minecraft:stone",
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(), null);
        BlockSnapshot replaced = new BlockSnapshot(
                org.bukkit.Material.AIR, "minecraft:air",
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(), null);
        return new BlockBreakRecord(
                id, 1, "break", now, now.plusSeconds(60 * 60 * 24 * 30),
                Origin.player(),
                Source.player(playerId, "player-" + (seed & 0xFF)),
                new BlockLocation(worldId, "world",
                        rng.nextInt(2000) - 1000, rng.nextInt(256),
                        rng.nextInt(2000) - 1000),
                "STONE", original, replaced);
    }

    private static long envLong(String key, long fallback) {
        String v = System.getenv(key);
        if (v == null) v = System.getProperty(key);
        if (v == null) return fallback;
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static long nanosToMillis(long nanos) {
        return TimeUnit.NANOSECONDS.toMillis(nanos);
    }

    private static void updateMax(AtomicLong hw, long sample) {
        long prev;
        while (sample > (prev = hw.get())) {
            if (hw.compareAndSet(prev, sample)) break;
        }
    }

    private static long[] toSortedArray(ConcurrentLinkedQueue<Long> queue) {
        long[] arr = queue.stream().mapToLong(Long::longValue).toArray();
        Arrays.sort(arr);
        return arr;
    }

    private static long percentile(long[] sorted, double p) {
        if (sorted.length == 0) return 0L;
        int idx = Math.min(sorted.length - 1, Math.max(0, (int) Math.round(p * (sorted.length - 1))));
        return sorted[idx];
    }

    // -------- harness ------------------------------------------------------

    /**
     * Pipeline-under-test handle: a producer-side submit callback, plus
     * counters and a flush/close. Not a real interface — the v2 and v1
     * harness factories wire everything up and return a ready-to-use
     * instance. Fields are mutable because the factories need to share
     * state between the submit closure, the wrapping store, and the flush
     * callback.
     */
    private static final class Harness {
        final String label;
        /** (record, recordLatency) -> submit to pipeline. recordLatency=false skips histogram (warmup). */
        java.util.function.BiConsumer<EventRecord, Boolean> submitFn;
        /** Live queue depth (non-authoritative for v2 — approximated from offered-saved). */
        java.util.function.LongSupplier queueDepthFn = () -> 0L;
        /** Total records persisted to Mongo (authoritative). */
        java.util.function.LongSupplier savedFn = () -> 0L;
        /** Total records dropped by the pipeline (authoritative post-flush). */
        java.util.function.LongSupplier droppedFn = () -> 0L;
        /** Block until all in-flight records are persisted or best-effort timeout. */
        Runnable flushFn = () -> {};
        /** Per-record ingest latencies (submit -> Mongo ack), nanoseconds. */
        ConcurrentLinkedQueue<Long> latencyNanos = new ConcurrentLinkedQueue<>();

        private Harness(String label) {
            this.label = label;
        }

        // ---------- v2 factory ----------

        static Harness v2(String mongoUri, int warnThreshold) {
            Harness h = new Harness("v2");
            String db = "SpyglassBench_" + System.nanoTime();
            // The MongoRecordStore builds its own MongoClient internally;
            // the URI carries uuidRepresentation + all connection state.
            SpyglassConfig.Database cfg = new SpyglassConfig.Database(
                    mongoUri, db, "EventRecords");
            MongoRecordStore realStore = new MongoRecordStore(cfg, new IndexManager());

            ConcurrentHashMap<UUID, Long> submitMap = new ConcurrentHashMap<>();
            AtomicLong savedCount = new AtomicLong();
            AtomicLong offeredCount = new AtomicLong();
            AtomicLong droppedFinal = new AtomicLong();

            RecordStore wrapping = new RecordStore() {
                @Override
                public void save(List<EventRecord> records) {
                    realStore.save(records);
                    long now = System.nanoTime();
                    for (EventRecord r : records) {
                        Long submit = submitMap.remove(r.id());
                        if (submit != null && submit != 0L) {
                            h.latencyNanos.add(now - submit);
                        }
                        savedCount.incrementAndGet();
                    }
                }

                @Override
                public QueryResult query(QueryRequest request) {
                    return realStore.query(request);
                }

                @Override
                public void close() {
                    realStore.close();
                }
            };
            AsyncRecorder recorder = new AsyncRecorder(warnThreshold, wrapping, BENCH_LOG);

            h.submitFn = (rec, recordLatency) -> {
                submitMap.put(rec.id(), recordLatency ? System.nanoTime() : 0L);
                recorder.record(rec);
                offeredCount.incrementAndGet();
            };
            h.queueDepthFn = () -> Math.max(0L,
                    offeredCount.get() - savedCount.get() - droppedFinal.get());
            h.savedFn = savedCount::get;
            h.droppedFn = droppedFinal::get;
            h.flushFn = () -> {
                AsyncRecorder.ShutdownReport report = recorder.shutdown(Duration.parse("60s"));
                droppedFinal.set(report.dropped());
                // recorder.shutdown already called save on any remaining
                // queue contents; realStore.close happens inside the
                // wrapping store's close via shutdown -> flushRemaining.
                // But the wrapping close is only called if we explicitly
                // call realStore.close(), and AsyncRecorder doesn't own
                // the store. Close it explicitly.
                realStore.close();
            };
            return h;
        }

        // ---------- v1 factory ----------

        /**
         * Synthetic reconstruction of v1's ingest pipeline. Unbounded
         * deque + 50ms scheduled drain + bulkWrite with v1-shape docs.
         * Uses the shared MongoClient (configured by the bench with
         * uuidRepresentation=standard via the container URI).
         */
        static Harness v1(MongoClient shared) {
            Harness h = new Harness("v1");
            String db = "v1Bench_" + System.nanoTime();
            MongoDatabase database = shared.getDatabase(db);
            MongoCollection<Document> coll = database.getCollection("DataEntry");
            coll.createIndex(Indexes.compoundIndex(
                    Indexes.ascending("Location.X"), Indexes.ascending("Location.Z"),
                    Indexes.ascending("Location.Y"), Indexes.descending("Created")));
            coll.createIndex(Indexes.compoundIndex(
                    Indexes.descending("Created"), Indexes.ascending("EventName")));
            coll.createIndex(Indexes.ascending("Expires"),
                    new IndexOptions().expireAfter(0L, TimeUnit.SECONDS));

            LinkedBlockingDeque<Document> queue = new LinkedBlockingDeque<>();
            ConcurrentHashMap<UUID, Long> submitMap = new ConcurrentHashMap<>();
            AtomicLong savedCount = new AtomicLong();
            AtomicLong offeredCount = new AtomicLong();
            BulkWriteOptions bulkOpts = new BulkWriteOptions().ordered(false);

            // v1's EntryQueueRunner runs on the Bukkit scheduler — 20Hz by
            // default (50ms period). We mirror that. Each tick drains the
            // entire queue into one bulkWrite, same as EntryQueueRunner#run.
            ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
                    r -> {
                        Thread t = new Thread(r, "bench-v1-drain");
                        t.setDaemon(true);
                        return t;
                    });
            Runnable drainTick = () -> {
                try {
                    List<Document> batch = new ArrayList<>();
                    queue.drainTo(batch);
                    if (batch.isEmpty()) return;
                    List<WriteModel<Document>> models = new ArrayList<>(batch.size());
                    for (Document doc : batch) models.add(new InsertOneModel<>(doc));
                    coll.bulkWrite(models, bulkOpts);
                    long now = System.nanoTime();
                    for (Document doc : batch) {
                        String ridStr = doc.getString("_benchId");
                        if (ridStr != null) {
                            UUID rid = UUID.fromString(ridStr);
                            Long submit = submitMap.remove(rid);
                            if (submit != null && submit != 0L) {
                                h.latencyNanos.add(now - submit);
                            }
                        }
                        savedCount.incrementAndGet();
                    }
                } catch (RuntimeException ex) {
                    BENCH_LOG.warning("v1 drain failed: " + ex.getMessage());
                }
            };
            scheduler.scheduleAtFixedRate(drainTick, 0, 50, TimeUnit.MILLISECONDS);

            h.submitFn = (rec, recordLatency) -> {
                Document doc = toV1Document(rec);
                // Stored as String so the shared MongoClient (no
                // uuidRepresentation set) can still encode it — v1's real
                // world behaviour stores player IDs as strings too.
                doc.put("_benchId", rec.id().toString());
                submitMap.put(rec.id(), recordLatency ? System.nanoTime() : 0L);
                queue.add(doc);
                offeredCount.incrementAndGet();
            };
            h.queueDepthFn = queue::size;
            h.savedFn = savedCount::get;
            h.droppedFn = () -> 0L;
            h.flushFn = () -> {
                // Stop the periodic scheduler, then one final synchronous
                // drain to catch any tail that arrived between ticks.
                scheduler.shutdown();
                try {
                    scheduler.awaitTermination(5, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                // Drain the rest synchronously.
                long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
                while (!queue.isEmpty() && System.nanoTime() < deadlineNanos) {
                    drainTick.run();
                }
            };
            return h;
        }

        private static Document toV1Document(EventRecord rec) {
            Document doc = new Document();
            doc.put("Event", rec.event());
            doc.put("EventName", rec.event());
            doc.put("Created", Date.from(rec.occurred()));
            doc.put("Expires", Date.from(rec.expiresAt()));
            if (rec.source() != null && rec.source().playerId() != null) {
                doc.put("Player", rec.source().playerId().toString());
                doc.put("Cause", rec.source().playerName());
            } else {
                doc.put("Cause", "environment");
            }
            if (rec.target() != null) {
                doc.put("Target", rec.target());
                doc.put("MaterialType", rec.target());
            }
            BlockLocation loc = rec.location();
            if (loc != null) {
                Document locDoc = new Document();
                locDoc.put("X", loc.x());
                locDoc.put("Y", loc.y());
                locDoc.put("Z", loc.z());
                locDoc.put("World", loc.worldId() != null ? loc.worldId().toString() : null);
                doc.put("Location", locDoc);
            }
            return doc;
        }
    }

    // -------- result --------

    private static class ScenarioResult {
        String label;
        long offered;
        long saved;
        long dropped;
        long producerElapsedMs;
        long totalElapsedMs;
        long queueHighWater;
        ConcurrentLinkedQueue<Long> latencyNanos;

        double observedRatePerSec() {
            if (totalElapsedMs <= 0) return 0;
            return saved * 1000.0 / totalElapsedMs;
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("label", label);
            m.put("offered", offered);
            m.put("saved", saved);
            m.put("dropped", dropped);
            m.put("producer_elapsed_ms", producerElapsedMs);
            m.put("total_elapsed_ms", totalElapsedMs);
            m.put("observed_rate_per_sec", Math.round(observedRatePerSec()));
            m.put("queue_high_water", queueHighWater);
            long[] lat = toSortedArray(latencyNanos);
            m.put("latency_samples", (long) lat.length);
            m.put("latency_p50_us", percentile(lat, 0.50) / 1_000);
            m.put("latency_p90_us", percentile(lat, 0.90) / 1_000);
            m.put("latency_p95_us", percentile(lat, 0.95) / 1_000);
            m.put("latency_p99_us", percentile(lat, 0.99) / 1_000);
            m.put("latency_max_us", lat.length == 0 ? 0 : lat[lat.length - 1] / 1_000);
            return m;
        }
    }
}
