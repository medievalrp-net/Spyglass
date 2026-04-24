package net.medievalrp.spyglass.plugin.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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

/**
 * Concurrency-focused tests for {@link AsyncRecorder} that complement
 * the functional coverage in {@link AsyncRecorderTest}. Every v1
 * listener calls {@code recorder.record(...)} from the Bukkit server
 * thread, but ingestion spikes (mass block-break, lag-recovery, plugin
 * tasks) can queue hundreds of events in a single tick. These tests
 * hammer {@code record()} from many OS threads simultaneously to prove:
 * <ul>
 *   <li>the {@link java.util.concurrent.LinkedBlockingDeque} offer path
 *       is lock-free and lossless under heavy fan-in;</li>
 *   <li>the no-drop invariant holds even when the store is slow — backlog
 *       grows in the queue rather than turning into lost events;</li>
 *   <li>a hard shutdown during active drain completes bounded-time and
 *       reports consistent counts ({@code drained + dropped + remaining
 *       <= offered}).</li>
 * </ul>
 */
class AsyncRecorderConcurrencyTest {

    private static JoinRecord sampleRecord() {
        Instant now = Instant.now();
        return new JoinRecord(
                UUID.randomUUID(), 1, "join", now, now.plusSeconds(60),
                Origin.player(),
                Source.player(UUID.randomUUID(), "tester"),
                new BlockLocation(UUID.randomUUID(), "world", 0, 64, 0),
                "tester", "127.0.0.1");
    }

    @Test
    void manyProducersReachStoreWithoutLoss() throws Exception {
        // 16 OS threads × 1 000 records each = 16 000 events. Warn
        // threshold 20 000 — deliberately higher than the total so the
        // warn path stays silent. With the unbounded queue, every record
        // must land in the store and drop count must be zero. This pins
        // the no-loss guarantee under realistic fan-in.
        int producers = 16;
        int perProducer = 1_000;
        CapturingStore store = new CapturingStore();
        AsyncRecorder recorder = new AsyncRecorder(20_000, store, Logger.getLogger("test"));

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(producers);
        try {
            for (int t = 0; t < producers; t++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < perProducer; i++) {
                            recorder.record(sampleRecord());
                        }
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();

            int expected = producers * perProducer;
            long deadline = System.currentTimeMillis() + 15_000L;
            while (System.currentTimeMillis() < deadline && store.totalSaved() < expected) {
                Thread.sleep(50L);
            }
            assertThat(store.totalSaved())
                    .as("every enqueued record must reach the store when queue has headroom")
                    .isEqualTo(expected);
        } finally {
            AsyncRecorder.ShutdownReport report = recorder.shutdown(Duration.parse("5s"));
            assertThat(report.dropped())
                    .as("no-drop invariant: fan-in from many producers must never drop")
                    .isZero();
        }
    }

    @Test
    void noDropsUnderHeavyContentionEvenWithSlowStore() throws Exception {
        // v2 no-drop guarantee: even when the store is artificially slow
        // and 8 producers hammer {@code record()} with 2 000 events each,
        // nothing must be lost. Backlog grows in the unbounded queue
        // (warn threshold fires, but no drop); shutdown has a generous
        // flush window so everything lands in the store.
        //
        // Invariants:
        //   1. dropped == 0 (Mongo is healthy, no catastrophic flush-timeout)
        //   2. drained + remaining == offered (every record is accounted for)
        //   3. counter atomicity under contention — no lost increments
        int producers = 8;
        int perProducer = 2_000;
        long warnThreshold = 25;
        // Slow the drain so the queue stays hot. With an unbounded queue
        // this builds backlog instead of drops.
        SlowStore store = new SlowStore(2L);
        AsyncRecorder recorder = new AsyncRecorder(warnThreshold, store, Logger.getLogger("test"));

        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger offered = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(producers);
        try {
            for (int t = 0; t < producers; t++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < perProducer; i++) {
                            recorder.record(sampleRecord());
                            offered.incrementAndGet();
                        }
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            // Generous flush window — slow store needs time to drain the
            // whole backlog at 2ms per save (~16k saves in worst case,
            // but batching brings that way down).
            AsyncRecorder.ShutdownReport report = recorder.shutdown(Duration.parse("30s"));
            int totalOffered = offered.get();
            long accounted = report.drained() + report.dropped() + report.remaining();
            assertThat(accounted)
                    .as("drained + dropped + remaining must equal offered; mismatch => lost counter increment")
                    .isEqualTo(totalOffered);
            assertThat(report.dropped())
                    .as("no-drop invariant: slow store must produce zero drops, not lost events")
                    .isZero();
            assertThat(report.drained())
                    .as("every offered record must have been persisted by shutdown end")
                    .isEqualTo(totalOffered);
        }
    }

    @Test
    void shutdownDuringActiveIngestIsBoundedAndConsistent() throws Exception {
        // Model the plugin disable scenario: events are still coming in
        // (a listener called record() mid-shutdown) and we yank the
        // recorder. Shutdown must complete within the configured
        // timeout and its report must sum correctly.
        CapturingStore store = new CapturingStore();
        AsyncRecorder recorder = new AsyncRecorder(10_000, store, Logger.getLogger("test"));
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger offered = new AtomicInteger();

        Thread producer = new Thread(() -> {
            try {
                start.await();
                long endAt = System.currentTimeMillis() + 500L;
                while (System.currentTimeMillis() < endAt) {
                    recorder.record(sampleRecord());
                    offered.incrementAndGet();
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        producer.start();
        start.countDown();

        // Let the producer enqueue for ~100ms before yanking.
        Thread.sleep(100L);
        long shutdownStart = System.nanoTime();
        AsyncRecorder.ShutdownReport report = recorder.shutdown(Duration.parse("5s"));
        long shutdownMs = (System.nanoTime() - shutdownStart) / 1_000_000L;
        producer.join(2_000);

        assertThat(shutdownMs)
                .as("shutdown must complete within the configured timeout + slack")
                .isLessThan(7_000L);
        long accounted = report.drained() + report.dropped() + report.remaining();
        // The report snapshot is taken before shutdown returns; the producer
        // may call record() again after that snapshot, so `accounted` is a
        // lower bound, bounded above by the final offered count.
        assertThat(accounted)
                .as("report counters cannot exceed the number of records offered to the recorder")
                .isLessThanOrEqualTo(offered.get());
        assertThat(report.dropped())
                .as("no-drop invariant: mid-ingest shutdown under a healthy store must not drop")
                .isZero();
        // Sanity — producer actually made progress before the yank.
        assertThat(offered.get()).isGreaterThan(0);
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

    /**
     * Store that sleeps per save call so the drain can't keep pace with
     * aggressive producers. Good for forcing the queue-full branch.
     */
    private static final class SlowStore implements RecordStore {
        private final long perSaveMs;
        private final AtomicInteger saved = new AtomicInteger();

        SlowStore(long perSaveMs) {
            this.perSaveMs = perSaveMs;
        }

        @Override
        public void save(List<EventRecord> records) {
            try {
                Thread.sleep(perSaveMs);
            } catch (InterruptedException ex) {
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
