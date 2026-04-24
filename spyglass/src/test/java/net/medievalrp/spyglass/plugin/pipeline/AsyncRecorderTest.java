package net.medievalrp.spyglass.plugin.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
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

class AsyncRecorderTest {

    private static JoinRecord sampleRecord() {
        Instant now = Instant.now();
        return new JoinRecord(
                UUID.randomUUID(),
                1,
                "join",
                now,
                now.plusSeconds(60),
                Origin.player(),
                Source.player(UUID.randomUUID(), "tester"),
                new BlockLocation(UUID.randomUUID(), "world", 0, 64, 0),
                "tester",
                "127.0.0.1");
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
