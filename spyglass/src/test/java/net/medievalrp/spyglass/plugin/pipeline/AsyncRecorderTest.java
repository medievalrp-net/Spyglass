package net.medievalrp.spyglass.plugin.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import net.medievalrp.spyglass.api.event.EventRecord;
import net.medievalrp.spyglass.api.event.JoinRecord;
import net.medievalrp.spyglass.api.event.Origin;
import net.medievalrp.spyglass.api.event.Source;
import net.medievalrp.spyglass.api.query.QueryRequest;
import net.medievalrp.spyglass.api.query.QueryResult;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.api.util.Duration;
import net.medievalrp.spyglass.plugin.storage.RecordStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class AsyncRecorderTest {

    private static JoinRecord sampleRecord() {
        Instant now = Instant.now();
        return new JoinRecord(
                UUID.randomUUID(),
                "join",
                now,
                now.plusSeconds(60),
                Origin.player(),
                Source.player(UUID.randomUUID(), "tester"),
                new BlockLocation(UUID.randomUUID(), "world", 0, 64, 0),
                "test",
                "tester",
                "127.0.0.1");
    }

    /**
     * #168: when an {@link IngestStats} is installed, both {@link
     * AsyncRecorder#record} and {@link AsyncRecorder#recordAll} tally each
     * intake by event type. Without it (the default), the hot path is unaffected
     * (covered by every other test here, which never set it).
     */
    @Test
    @Timeout(10)
    void ingestStatsCountsRecordAndRecordAll() throws Exception {
        CapturingStore store = new CapturingStore();
        AsyncRecorder recorder = new AsyncRecorder(1000, store, Logger.getLogger("test"));
        IngestStats stats = new IngestStats(
                recorder::queueDepth, recorder::drainedCount, recorder::droppedCount, () -> -1L);
        recorder.setIngestStats(stats);
        try {
            recorder.record(sampleRecord());
            recorder.record(sampleRecord());
            recorder.recordAll(List.of(sampleRecord(), sampleRecord(), sampleRecord()));

            IngestStats.Snapshot snap = stats.capture(0L);
            assertThat(snap.total())
                    .as("record() x2 + recordAll() of 3 = 5 intakes counted")
                    .isEqualTo(5L);
            assertThat(snap.counts()).containsEntry("join", 5L);
        } finally {
            recorder.shutdown(Duration.parse("2s"));
        }
    }

    @Test
    void drainsBatchesToStore() throws Exception {
        CapturingStore store = new CapturingStore();
        AsyncRecorder recorder = new AsyncRecorder(1000, store, Logger.getLogger("test"));
        try {
            for (int index = 0; index < 50; index++) {
                recorder.record(sampleRecord());
            }
            long deadline = System.currentTimeMillis() + 3_000L;
            while (System.currentTimeMillis() < deadline && store.totalSaved() < 50) {
                Thread.sleep(50L);
            }
            assertThat(store.totalSaved()).isEqualTo(50);
        } finally {
            recorder.shutdown(Duration.parse("2s"));
        }
    }

    @Test
    void neverDropsAtIntakeEvenWhenStoreIsStalled() throws Exception {
        // No-drop invariant: record() must accept every event, no matter how
        // far behind the drain is. The warnThreshold is a soft signal — far
        // below the offered count here — and must NOT cause drops. Once the
        // store opens and shutdown runs, every offered record lands in the
        // store; dropped stays zero.
        LatchedStore store = new LatchedStore();
        AsyncRecorder recorder = new AsyncRecorder(5, store, Logger.getLogger("test"));
        try {
            for (int index = 0; index < 50; index++) {
                recorder.record(sampleRecord());
            }
            // Let the drain proceed so shutdown can complete.
            store.open();
            AsyncRecorder.ShutdownReport report = recorder.shutdown(Duration.parse("3s"));
            assertThat(report.dropped())
                    .as("no-drop invariant: intake must never reject records")
                    .isZero();
            assertThat(report.remaining())
                    .as("all queued records must flush before shutdown returns")
                    .isZero();
            assertThat(store.totalSaved())
                    .as("every offered record must reach the store")
                    .isEqualTo(50);
        } finally {
            store.open();
        }
    }

    @Test
    void flushReturnsFalseWhenBarrierTimesOut() {
        // #98: if the deferred-serialization stage can't drain in time, flush
        // must NOT claim success — a rollback relying on it would otherwise
        // read before the in-flight records landed (partial restore).
        CapturingStore store = new CapturingStore();
        AsyncRecorder recorder = new AsyncRecorder(1000, store, Logger.getLogger("test"));
        try {
            recorder.setFlushBarrier(timeout -> false);
            assertThat(recorder.flush(Duration.parse("1s"))).isFalse();
        } finally {
            recorder.shutdown(Duration.parse("2s"));
        }
    }

    @Test
    void flushAwaitsBarrierThenDrainsQueue() {
        // #98: flush drains the serialization barrier first, then the queue.
        CapturingStore store = new CapturingStore();
        AsyncRecorder recorder = new AsyncRecorder(1000, store, Logger.getLogger("test"));
        try {
            AtomicBoolean barrierCalled = new AtomicBoolean(false);
            recorder.setFlushBarrier(timeout -> {
                barrierCalled.set(true);
                return true;
            });
            for (int index = 0; index < 10; index++) {
                recorder.record(sampleRecord());
            }
            assertThat(recorder.flush(Duration.parse("3s"))).isTrue();
            assertThat(barrierCalled)
                    .as("flush must drain the serialization barrier before the queue")
                    .isTrue();
            assertThat(store.totalSaved()).isEqualTo(10);
        } finally {
            recorder.shutdown(Duration.parse("2s"));
        }
    }

    @Test
    void flushesRemainingOnShutdown() {
        CapturingStore store = new CapturingStore();
        AsyncRecorder recorder = new AsyncRecorder(1000, store, Logger.getLogger("test"));
        for (int index = 0; index < 10; index++) {
            recorder.record(sampleRecord());
        }
        AsyncRecorder.ShutdownReport report = recorder.shutdown(Duration.parse("3s"));
        assertThat(report.remaining()).isZero();
        assertThat(store.totalSaved()).isEqualTo(10);
    }

    @Test
    void shutdownFlushExhaustionCountsDroppedRecordsAndLogsSevere() throws Exception {
        // The single remaining loss path: Mongo is unreachable through the
        // entire shutdown flush-timeout. Under normal availability this is
        // unreachable, but we still need to prove the accounting works —
        // dropped is incremented exactly by the unflushed batch size,
        // drained stays zero, remaining stays zero (the queue was drained
        // into the batch even though the batch never persisted), and the
        // ShutdownReport invariants sum correctly.
        AlwaysFailingStore store = new AlwaysFailingStore();
        AsyncRecorder recorder = new AsyncRecorder(1000, store, Logger.getLogger("test"));
        for (int index = 0; index < 7; index++) {
            recorder.record(sampleRecord());
        }
        // Tight deadline — exhaust the retry budget quickly. Each backoff
        // is min(2000ms, 100 << min(attempt-1, 4)) so a 1s budget gives
        // roughly one or two retry attempts before giving up.
        AsyncRecorder.ShutdownReport report = recorder.shutdown(Duration.parse("1s"));

        assertThat(report.dropped())
                .as("dropped must equal the records the flush couldn't persist")
                .isEqualTo(7L);
        assertThat(report.drained())
                .as("nothing reached the store; drained stays zero")
                .isZero();
        assertThat(report.remaining())
                .as("the flush drained the queue into the failing batch; remaining is zero")
                .isZero();
        assertThat(store.attempts())
                .as("the flush must have attempted at least one save before giving up")
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    void drainRecoversAfterTransientStoreFailures() throws Exception {
        // Regression for the silent-death bug: a Mongo hiccup used to kill the
        // virtual drain thread permanently. Verify the drain now retries the
        // same batch and keeps running after a RuntimeException from the store.
        FailingThenSucceedingStore store = new FailingThenSucceedingStore(2);
        AsyncRecorder recorder = new AsyncRecorder(100, store, Logger.getLogger("test"));
        try {
            recorder.record(sampleRecord());
            long deadline = System.currentTimeMillis() + 5_000L;
            while (System.currentTimeMillis() < deadline && store.totalSaved() < 1) {
                Thread.sleep(50L);
            }
            assertThat(store.totalSaved())
                    .as("drain loop must recover after transient failures")
                    .isEqualTo(1);
            assertThat(store.attempts()).isGreaterThanOrEqualTo(3);

            // Drain thread must still be alive: new records should flow too.
            recorder.record(sampleRecord());
            deadline = System.currentTimeMillis() + 3_000L;
            while (System.currentTimeMillis() < deadline && store.totalSaved() < 2) {
                Thread.sleep(50L);
            }
            assertThat(store.totalSaved()).isEqualTo(2);
        } finally {
            recorder.shutdown(Duration.parse("2s"));
        }
    }

    @Test
    void drainRunsOnANamedDaemonPlatformThread() throws Exception {
        // #115: the drain is one perpetual blocking-I/O loop, so it runs on a
        // dedicated platform thread, not a virtual thread — a virtual thread's
        // per-record wakeup paid ForkJoinPool continuation-submission on the
        // ingest hot path. A virtual thread does NOT appear in
        // Thread.getAllStackTraces() (it's outside the thread-group
        // enumeration); a platform thread does. So finding the named thread
        // there at all is itself proof it's a platform thread; we also assert
        // isVirtual() is false and it's a daemon (never holds JVM exit).
        CapturingStore store = new CapturingStore();
        AsyncRecorder recorder = new AsyncRecorder(1000, store, Logger.getLogger("test"));
        try {
            Thread drain = null;
            long deadline = System.currentTimeMillis() + 2_000L;
            while (System.currentTimeMillis() < deadline && drain == null) {
                drain = Thread.getAllStackTraces().keySet().stream()
                        .filter(t -> "spyglass-drain".equals(t.getName()))
                        .findFirst()
                        .orElse(null);
                if (drain == null) {
                    Thread.sleep(20L);
                }
            }
            assertThat(drain)
                    .as("drain must run on a platform thread (virtual threads are "
                            + "absent from getAllStackTraces)")
                    .isNotNull();
            assertThat(drain.isVirtual())
                    .as("drain must not be a virtual thread")
                    .isFalse();
            assertThat(drain.isDaemon())
                    .as("drain must be a daemon so it never blocks JVM exit")
                    .isTrue();
        } finally {
            recorder.shutdown(Duration.parse("2s"));
        }
    }

    @Test
    @Timeout(15)
    void offMainProducerBackpressuresAtQueueMaxThenResumesLosslessly() throws Exception {
        // #119: an off-main bulk-edit firehose (WorldEdit/FAWE) must not be
        // able to grow the queue past queue-max into an OOM. With the store
        // wedged, the producer fills to the ceiling then PARKS — admitted
        // freezes well below the total — and resumes losslessly once the drain
        // can move again. () -> false marks every caller as off-main.
        long queueMax = 50L;
        int total = 5_000;
        GatedStore store = new GatedStore();
        AsyncRecorder recorder = new AsyncRecorder(
                1_000L, queueMax, store, new WalDurability(null, false, Logger.getLogger("test")),
                () -> false, Logger.getLogger("test"));
        AtomicInteger admitted = new AtomicInteger();
        Thread producer = new Thread(() -> {
            for (int i = 0; i < total; i++) {
                recorder.record(sampleRecord());
                admitted.incrementAndGet();
            }
        }, "test-producer");
        try {
            producer.start();
            // The drain takes one batch and blocks in the wedged save(); no
            // space frees after that, so the producer parks at the ceiling.
            // Sample twice — a frozen count proves backpressure, not just slow.
            Thread.sleep(500L);
            int sample1 = admitted.get();
            Thread.sleep(500L);
            int sample2 = admitted.get();
            assertThat(sample2)
                    .as("off-main producer must park at the ceiling, not admit the whole burst")
                    .isLessThan(total);
            assertThat(sample2)
                    .as("admitted must be frozen while the drain is wedged (backpressure, not slow)")
                    .isEqualTo(sample1);

            // Let the drain move; the parked producer must resume and finish.
            store.open();
            producer.join(10_000L);
            assertThat(admitted.get())
                    .as("every record is eventually admitted once the drain catches up")
                    .isEqualTo(total);
            long deadline = System.currentTimeMillis() + 10_000L;
            while (System.currentTimeMillis() < deadline && store.totalSaved() < total) {
                Thread.sleep(25L);
            }
            assertThat(store.totalSaved())
                    .as("no records lost: backpressure paces the firehose, it does not drop")
                    .isEqualTo(total);
        } finally {
            store.open();
            recorder.shutdown(Duration.parse("3s"));
        }
    }

    @Test
    @Timeout(15)
    void mainThreadProducerIsNeverBlockedByTheCeiling() throws Exception {
        // #119: the Bukkit primary thread must never park on the ceiling — its
        // low-rate audit events are always admitted (a small overshoot), so a
        // tick can't stall. () -> true marks the caller as primary; with the
        // store wedged and a tiny ceiling, a blocking impl would deadlock this
        // thread, so completing the loop at all is the proof.
        GatedStore store = new GatedStore();
        AsyncRecorder recorder = new AsyncRecorder(
                1_000L, 10L, store, new WalDurability(null, false, Logger.getLogger("test")),
                () -> true, Logger.getLogger("test"));
        try {
            for (int i = 0; i < 100; i++) {
                recorder.record(sampleRecord());
            }
            // Reached here without hanging => the primary thread was never
            // blocked even though the queue is far past its 10-record ceiling.
            store.open();
            long deadline = System.currentTimeMillis() + 10_000L;
            while (System.currentTimeMillis() < deadline && store.totalSaved() < 100) {
                Thread.sleep(25L);
            }
            assertThat(store.totalSaved())
                    .as("main-thread records are admitted and persist; none dropped")
                    .isEqualTo(100);
        } finally {
            store.open();
            AsyncRecorder.ShutdownReport report = recorder.shutdown(Duration.parse("3s"));
            assertThat(report.dropped())
                    .as("main-thread admit path drops nothing")
                    .isZero();
        }
    }

    @Test
    @Timeout(20)
    void recordAllSpillsToDiskAtCeilingThenDrainsLosslessly(@TempDir Path dataFolder) throws Exception {
        // #122: the uncappable vanilla-WorldEdit firehose (recordAll) must spill
        // its overflow to disk at the ceiling instead of holding it in RAM or
        // blocking — so a giant paste stays heap-flat and loses nothing. With
        // the store wedged, the queue caps and the bulk lands on disk; once the
        // store opens, the drain replays queue + spill and every record persists.
        long queueMax = 200L;
        int batchSize = 100;
        int batches = 40; // 4000 records — 20x the ceiling
        int total = batchSize * batches;
        SpillBuffer spill = new SpillBuffer(dataFolder, true, Logger.getLogger("test"));
        GatedStore store = new GatedStore();
        AsyncRecorder recorder = new AsyncRecorder(
                1_000L, queueMax, store, new WalDurability(null, false, Logger.getLogger("test")),
                spill, () -> false, Logger.getLogger("test"));
        AtomicInteger submitted = new AtomicInteger();
        Thread producer = new Thread(() -> {
            for (int b = 0; b < batches; b++) {
                List<EventRecord> batch = new ArrayList<>(batchSize);
                for (int i = 0; i < batchSize; i++) {
                    batch.add(sampleRecord());
                }
                recorder.recordAll(batch);
                submitted.incrementAndGet();
            }
        }, "we-firehose");
        try {
            producer.start();
            // recordAll spills instead of parking, so the producer finishes the
            // whole burst even though the store is wedged the entire time.
            producer.join(8_000L);
            assertThat(submitted.get())
                    .as("recordAll must not block at the ceiling — it spills overflow to disk")
                    .isEqualTo(batches);
            assertThat(spill.hasPending())
                    .as("the over-ceiling overflow must have gone to disk, not RAM")
                    .isTrue();
            assertThat(spill.pendingRecordCount())
                    .as("the bulk of the burst is on disk; only ~queue-max stays in RAM")
                    .isGreaterThan(total / 2L);

            // Open the store: the drain replays queue + spill, losslessly.
            store.open();
            long deadline = System.currentTimeMillis() + 12_000L;
            while (System.currentTimeMillis() < deadline && store.totalSaved() < total) {
                Thread.sleep(25L);
            }
            assertThat(store.totalSaved())
                    .as("every record persists — spilled overflow is replayed, nothing dropped")
                    .isEqualTo(total);
            assertThat(spill.hasPending())
                    .as("all spilled segments are acked (deleted) after replay")
                    .isFalse();
        } finally {
            store.open();
            AsyncRecorder.ShutdownReport report = recorder.shutdown(Duration.parse("3s"));
            assertThat(report.dropped()).as("the spill path drops nothing").isZero();
        }
    }

    private static List<EventRecord> sampleBatch(int n) {
        List<EventRecord> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(sampleRecord());
        }
        return out;
    }

    @Test
    @Timeout(15)
    void recordAllSpillsOnTheMainThreadWhenTheQueueIsFull(@TempDir Path dataFolder) throws Exception {
        // #121: when a huge WorldEdit op saturates the off-thread build pool it
        // builds inline on the MAIN thread, and that inline recordAll must spill
        // to disk — not fall through to the main-thread always-admit path, which
        // would grow the queue unbounded again. So recordAll spills once the
        // queue is at the ceiling regardless of thread.
        SpillBuffer spill = new SpillBuffer(dataFolder, true, Logger.getLogger("test"));
        GatedStore store = new GatedStore();
        AsyncRecorder recorder = new AsyncRecorder(
                1_000L, 10L, store, new WalDurability(null, false, Logger.getLogger("test")),
                spill, () -> true, Logger.getLogger("test")); // () -> true: every caller is "main"
        try {
            // Several main-thread batches: the first fills the queue past the
            // ceiling, the rest must spill (the drain is wedged, so the queue
            // can't drain below the ceiling). None may block the main thread — a
            // blocking impl would deadlock against the wedged store.
            int batches = 5;
            int perBatch = 50;
            for (int b = 0; b < batches; b++) {
                recorder.recordAll(sampleBatch(perBatch));
            }
            assertThat(spill.hasPending())
                    .as("main-thread recordAll must spill once the queue is at the ceiling")
                    .isTrue();
            assertThat(spill.pendingRecordCount()).isGreaterThan(0L);

            store.open();
            int total = batches * perBatch;
            long deadline = System.currentTimeMillis() + 10_000L;
            while (System.currentTimeMillis() < deadline && store.totalSaved() < total) {
                Thread.sleep(25L);
            }
            assertThat(store.totalSaved())
                    .as("every record persists — queue + spill both replayed, nothing dropped")
                    .isEqualTo(total);
            assertThat(spill.hasPending()).isFalse();
        } finally {
            store.open();
            assertThat(recorder.shutdown(Duration.parse("3s")).dropped()).isZero();
        }
    }

    @Test
    @Timeout(25)
    void spillBacklogDrainsWhileTheQueueKeepsReceivingRecords(@TempDir Path dataFolder)
            throws Exception {
        // #180: the old drain only replayed spill when the in-RAM queue was
        // EMPTY, so a continuously-busy server never reclaimed the on-disk
        // backlog and the spill grew without bound (140 GB seen in prod). With a
        // steady stream of live records keeping the queue non-empty, the spill
        // backlog must still drain to empty.
        long queueMax = 1_000L;
        int segments = 6;
        int perSegment = 50;
        SpillBuffer spill = new SpillBuffer(dataFolder, true, Logger.getLogger("test"));
        // Pre-seed a backlog of spilled segments directly on disk.
        for (int s = 0; s < segments; s++) {
            spill.spill(sampleBatch(perSegment));
        }
        SlowGatedStore store = new SlowGatedStore();
        AsyncRecorder recorder = new AsyncRecorder(
                1_000_000L, queueMax, store, new WalDurability(null, false, Logger.getLogger("test")),
                spill, () -> true, Logger.getLogger("test"));
        AtomicBoolean producing = new AtomicBoolean(true);
        Thread producer = new Thread(() -> {
            while (producing.get()) {
                recorder.record(sampleRecord());
                try {
                    Thread.sleep(2L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "live-ingest");
        try {
            producer.start();
            // Let the producer fill the queue BEFORE the (slow) store is allowed to
            // drain, so the queue is already non-empty when the drain starts
            // looping — the busy-server condition the bug starved under. The gate
            // also guarantees no spill segment is acked before this point.
            Thread.sleep(200L);
            store.open(20L); // 20 ms/save keeps the queue non-empty at poll time

            // The backlog must drain to empty WHILE the producer is still feeding
            // the queue. The old queue-empty-only logic would leave it pending.
            long deadline = System.currentTimeMillis() + 10_000L;
            while (System.currentTimeMillis() < deadline && spill.hasPending()) {
                Thread.sleep(50L);
            }
            assertThat(spill.hasPending())
                    .as("spill backlog must drain even while the queue keeps receiving records")
                    .isFalse();
            assertThat(store.totalSaved())
                    .as("queue records flowed too — the queue was genuinely active while the "
                            + "backlog drained, not idle")
                    .isGreaterThan(segments * perSegment);
        } finally {
            producing.set(false);
            producer.join(2_000L);
            recorder.shutdown(Duration.parse("3s"));
        }
    }

    @Test
    @Timeout(15)
    void spillDrainRateCapPausesReplayWhenBudgetIsExhausted(@TempDir Path dataFolder)
            throws Exception {
        // #180: with a tiny rate cap the drain must NOT replay the whole backlog
        // at once — it paces the backfill. A 1 rec/s cap means a 300-record
        // backlog cannot fully drain within a short window; most of it stays on
        // disk. (The default cap is far higher; this just proves the throttle.)
        SpillBuffer spill = new SpillBuffer(dataFolder, true, Logger.getLogger("test"));
        for (int s = 0; s < 6; s++) {
            spill.spill(sampleBatch(50));
        }
        CapturingStore store = new CapturingStore();
        AsyncRecorder recorder = new AsyncRecorder(
                1_000_000L, 1_000L, store, new WalDurability(null, false, Logger.getLogger("test")),
                spill, () -> true, Logger.getLogger("test"));
        recorder.setSpillDrainRate(1L); // 1 record/sec — extremely slow on purpose
        try {
            // Initial budget is one second (one segment), then the cap throttles.
            // After a short wait, a strict cap must leave most of the backlog on disk.
            Thread.sleep(1_500L);
            assertThat(spill.pendingRecordCount())
                    .as("a 1 rec/s cap must pace the backfill, not flush 300 records at once")
                    .isGreaterThan(100L);
        } finally {
            recorder.setSpillDrainRate(0L); // uncap so shutdown drains it promptly
            recorder.shutdown(Duration.parse("5s"));
        }
    }

    /**
     * Store whose save() blocks until {@link #open(long)} is called, then sleeps
     * a fixed time per save — models a slow live store so the in-RAM queue stays
     * non-empty at poll time the way a real DB round-trip keeps it (#180).
     */
    private static final class SlowGatedStore implements RecordStore {
        private final CountDownLatch gate = new CountDownLatch(1);
        private volatile long sleepMillis = 0L;
        private final AtomicInteger saved = new AtomicInteger();

        void open(long perSaveSleepMillis) {
            this.sleepMillis = perSaveSleepMillis;
            gate.countDown();
        }

        int totalSaved() {
            return saved.get();
        }

        @Override
        public void save(List<EventRecord> records) {
            try {
                gate.await();
                if (sleepMillis > 0L) {
                    Thread.sleep(sleepMillis);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            saved.addAndGet(records.size());
        }

        @Override
        public QueryResult query(QueryRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close() {
        }
    }

    /**
     * Store whose save() blocks until {@link #open()} is called — models a
     * wedged drain so the backpressure ceiling engages deterministically. No
     * internal timeout: the test controls exactly when the drain may move.
     */
    private static final class GatedStore implements RecordStore {
        private final CountDownLatch gate = new CountDownLatch(1);
        private final AtomicInteger saved = new AtomicInteger();

        void open() {
            gate.countDown();
        }

        int totalSaved() {
            return saved.get();
        }

        @Override
        public void save(List<EventRecord> records) {
            try {
                gate.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            saved.addAndGet(records.size());
        }

        @Override
        public QueryResult query(QueryRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close() {
        }
    }

    private static final class CapturingStore implements RecordStore {
        private final CopyOnWriteArrayList<EventRecord> all = new CopyOnWriteArrayList<>();

        int totalSaved() {
            return all.size();
        }

        @Override
        public void save(List<EventRecord> records) {
            all.addAll(records);
        }

        @Override
        public QueryResult query(QueryRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close() {
        }
    }

    private static final class FailingThenSucceedingStore implements RecordStore {
        private final AtomicInteger failuresLeft;
        private final AtomicInteger attempts = new AtomicInteger();
        private final CopyOnWriteArrayList<EventRecord> persisted = new CopyOnWriteArrayList<>();

        FailingThenSucceedingStore(int initialFailures) {
            this.failuresLeft = new AtomicInteger(initialFailures);
        }

        int totalSaved() {
            return persisted.size();
        }

        int attempts() {
            return attempts.get();
        }

        @Override
        public void save(List<EventRecord> records) {
            attempts.incrementAndGet();
            if (failuresLeft.getAndDecrement() > 0) {
                throw new RuntimeException("simulated store failure");
            }
            persisted.addAll(records);
        }

        @Override
        public QueryResult query(QueryRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close() {
        }
    }

    /**
     * Store that always throws. Models a Mongo outage that lasts for the
     * entire shutdown flush window — the one path where AsyncRecorder
     * gives up and increments {@code dropped}.
     */
    private static final class AlwaysFailingStore implements RecordStore {
        private final AtomicInteger attempts = new AtomicInteger();

        int attempts() {
            return attempts.get();
        }

        @Override
        public void save(List<EventRecord> records) {
            attempts.incrementAndGet();
            throw new RuntimeException("simulated mongo outage");
        }

        @Override
        public QueryResult query(QueryRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close() {
        }
    }

    private static final class LatchedStore implements RecordStore {
        private final CountDownLatch gate = new CountDownLatch(1);
        private final AtomicInteger saved = new AtomicInteger();

        void open() {
            gate.countDown();
        }

        int totalSaved() {
            return saved.get();
        }

        @Override
        public void save(List<EventRecord> records) {
            try {
                gate.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            saved.addAndGet(records.size());
        }

        @Override
        public QueryResult query(QueryRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close() {
        }
    }
}
