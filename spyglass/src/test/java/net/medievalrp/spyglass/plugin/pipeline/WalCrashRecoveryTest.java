package net.medievalrp.spyglass.plugin.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import net.medievalrp.spyglass.api.event.ChatRecord;
import net.medievalrp.spyglass.api.event.EventRecord;
import net.medievalrp.spyglass.api.event.Origin;
import net.medievalrp.spyglass.api.event.Source;
import net.medievalrp.spyglass.api.query.QueryRequest;
import net.medievalrp.spyglass.api.query.QueryResult;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.api.util.Duration;
import net.medievalrp.spyglass.plugin.storage.RecordStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end coverage for the AsyncRecorder + WalDurability crash
 * recovery contract. Simulates a JVM crash mid-batch and verifies the
 * next process replays the WAL through to the store.
 */
class WalCrashRecoveryTest {

    private static final UUID PLAYER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID WORLD = UUID.fromString("77777777-7777-7777-7777-777777777777");

    @Test
    void recordsSurviveCrashBetweenFsyncAndAck(@TempDir Path tmp) throws Exception {
        // Round 1: a recorder that always fails the DB save. Records
        // get fsynced to the WAL, retry indefinitely, and the
        // process is "killed" by shutting down before any save
        // succeeds.
        FlakyStore alwaysFails = new FlakyStore(Integer.MAX_VALUE);
        WalDurability wal1 = new WalDurability(tmp, true, Logger.getLogger("crash-test-1"));
        AsyncRecorder recorder1 = new AsyncRecorder(10_000, alwaysFails, wal1, Logger.getLogger("rec-1"));

        List<EventRecord> originals = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            EventRecord r = chatAt(Instant.now(), "msg-" + i);
            originals.add(r);
            recorder1.record(r);
        }

        // Wait long enough that the drain thread has at least
        // attempted a save (and therefore fsynced the WAL).
        Thread.sleep(750);

        // Force shutdown with a tiny deadline; the store still
        // refuses, so records remain on disk.
        AsyncRecorder.ShutdownReport report1 = recorder1.shutdown(new Duration(1));
        assertThat(alwaysFails.attempts.get())
                .as("drain must have attempted at least one save before crash")
                .isGreaterThan(0);
        assertThat(alwaysFails.saved)
                .as("flaky store must have saved nothing")
                .isEmpty();
        // With WAL enabled, the shutdown path classifies undrained
        // records as recoverable (not dropped).
        assertThat(report1.dropped()).isZero();

        // Round 2: a fresh recorder with a working store. Boot-time
        // recovery replays the WAL.
        RecordingStore working = new RecordingStore();
        WalDurability wal2 = new WalDurability(tmp, true, Logger.getLogger("crash-test-2"));
        List<EventRecord> recovered = wal2.recover();
        assertThat(recovered)
                .as("WAL recover() must return the records left by the crashed recorder")
                .hasSizeGreaterThanOrEqualTo(originals.size());

        AsyncRecorder recorder2 = new AsyncRecorder(10_000, working, wal2, Logger.getLogger("rec-2"));
        for (EventRecord r : recovered) {
            recorder2.record(r);
        }
        AsyncRecorder.ShutdownReport report2 = recorder2.shutdown(new Duration(5));

        assertThat(report2.remaining()).isZero();
        assertThat(report2.dropped()).isZero();

        // Every original message ID must be present in the working
        // store. Duplicates are acceptable here — the storage layer
        // (Mongo _id collisions, ReplacingMergeTree) handles
        // dedup. The contract this test covers is "no record is
        // lost across the crash boundary."
        List<UUID> savedIds = working.allIds();
        for (EventRecord original : originals) {
            assertThat(savedIds)
                    .as("record %s must be present after WAL replay", original.id())
                    .contains(original.id());
        }
    }

    @Test
    void recoveryClearsPendingDirSoSecondBootIsClean(@TempDir Path tmp) throws Exception {
        FlakyStore alwaysFails = new FlakyStore(Integer.MAX_VALUE);
        WalDurability wal1 = new WalDurability(tmp, true, Logger.getLogger("clean-1"));
        AsyncRecorder recorder = new AsyncRecorder(10_000, alwaysFails, wal1, Logger.getLogger("rec-3"));
        recorder.record(chatAt(Instant.now(), "stranded"));
        Thread.sleep(500);
        recorder.shutdown(new Duration(1));

        WalDurability wal2 = new WalDurability(tmp, true, Logger.getLogger("clean-2"));
        assertThat(wal2.recover()).isNotEmpty();
        // Second recover() on the same WAL instance returns nothing
        // because recovery deletes pending files as it goes.
        assertThat(wal2.recover()).isEmpty();

        // A fresh WAL instance at the same path must also see
        // nothing — recovery is durable, not just in-memory.
        WalDurability wal3 = new WalDurability(tmp, true, Logger.getLogger("clean-3"));
        assertThat(wal3.recover()).isEmpty();
    }

    private static ChatRecord chatAt(Instant occurred, String message) {
        BlockLocation loc = new BlockLocation(WORLD, "world", 0, 64, 0);
        return new ChatRecord(UUID.randomUUID(), "say", occurred, occurred.plusSeconds(3600),
                Origin.player(), Source.player(PLAYER, "Tester"),
                loc, "Tester", message, List.of());
    }

    private static final class FlakyStore implements RecordStore {
        final AtomicInteger attempts = new AtomicInteger();
        final List<EventRecord> saved = new ArrayList<>();
        private final int failuresBeforeSuccess;

        FlakyStore(int failuresBeforeSuccess) {
            this.failuresBeforeSuccess = failuresBeforeSuccess;
        }

        @Override
        public synchronized void save(List<EventRecord> records) {
            int n = attempts.incrementAndGet();
            if (n <= failuresBeforeSuccess) {
                throw new RuntimeException("simulated DB outage (attempt " + n + ")");
            }
            saved.addAll(records);
        }

        @Override
        public QueryResult query(QueryRequest request) {
            return new QueryResult(List.of(), List.of());
        }

        @Override
        public QueryResult querySummary(QueryRequest request) {
            return query(request);
        }

        @Override
        public void close() {
        }
    }

    private static final class RecordingStore implements RecordStore {
        final ConcurrentLinkedQueue<EventRecord> all = new ConcurrentLinkedQueue<>();

        @Override
        public void save(List<EventRecord> records) {
            all.addAll(records);
        }

        @Override
        public QueryResult query(QueryRequest request) {
            return new QueryResult(List.of(), List.of());
        }

        @Override
        public QueryResult querySummary(QueryRequest request) {
            return query(request);
        }

        @Override
        public void close() {
        }

        List<UUID> allIds() {
            List<UUID> ids = new ArrayList<>();
            for (EventRecord r : all) {
                ids.add(r.id());
            }
            return ids;
        }
    }
}
