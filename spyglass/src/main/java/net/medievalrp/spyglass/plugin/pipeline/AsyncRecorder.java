package net.medievalrp.spyglass.plugin.pipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Logger;
import net.medievalrp.spyglass.api.event.EventRecord;
import net.medievalrp.spyglass.api.util.Duration;
import net.medievalrp.spyglass.plugin.storage.RecordStore;
import org.jetbrains.annotations.ApiStatus;

/**
 * Async event ingest pipeline. A virtual-thread drain batches records
 * out to the {@link RecordStore} with exponential-backoff retry on
 * transient failures.
 *
 * <h2>No-drop guarantee at intake</h2>
 *
 * The queue is <b>unbounded</b>. {@link #record} never rejects a record,
 * no matter how far behind the drain is. This matches the v1
 * v1 contract (every event that fires a listener reaches the persistence
 * pipeline) and is the behaviour operators expect from an
 * audit-logging plugin.
 *
 * <p>The {@code warnThreshold} passed to the constructor is a soft
 * signal, <i>not</i> a ceiling: crossing it logs a warning to give the
 * operator an early heads-up that Mongo may be lagging, but the queue
 * keeps accepting records. Warnings fire at the first crossing and at
 * doubling intervals thereafter, so a genuine outage surfaces its
 * growth shape in the log without flooding it.
 *
 * <h2>The only scenarios where records can be lost</h2>
 *
 * Under normal operation (server running, Mongo reachable) an event
 * that reaches {@link #record} always lands in the store. Two edge
 * cases still exist; both require spill-to-disk to eliminate, and v1
 * has the same exposure:
 *
 * <ol>
 *   <li><b>Hard JVM death</b> — server crash, SIGKILL, OOM, power loss.
 *       The queue is RAM-resident; anything not yet saved is lost with
 *       the process.</li>
 *   <li><b>Shutdown flush deadline exhaustion</b> —
 *       {@link #flushRemaining} retries with backoff for the full
 *       configured {@code flush-timeout}. If Mongo is still unreachable
 *       when the deadline expires, undrained records are counted into
 *       {@link ShutdownReport#dropped} and logged at SEVERE. Under
 *       normal Mongo availability this code path is unreachable.</li>
 * </ol>
 *
 * <p>Aside from those, records are durable once {@link #record} returns.
 */
@ApiStatus.Internal
public final class AsyncRecorder implements Recorder {

    private final LinkedBlockingDeque<EventRecord> queue = new LinkedBlockingDeque<>();
    private final long warnThreshold;
    private final RecordStore store;
    private final WalDurability wal;
    private final Logger logger;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final CountDownLatch stopped = new CountDownLatch(1);
    private final AtomicLong drained = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong lastWarnedDepth = new AtomicLong();
    private volatile Consumer<EventRecord> committedHook = r -> {};

    /**
     * @param warnThreshold queue depth at which we start warning the operator.
     *                      <b>Not a ceiling</b> — records past this depth are
     *                      still accepted. Sized for early warning: recommend
     *                      10× your steady-state peak so a warning precedes
     *                      any real backpressure problem by a long margin.
     *                      For MedievalRP's peak ~600 events/sec, a 100 000
     *                      threshold gives ~160 s of slack before the first
     *                      warning.
     * @param store         downstream sink — typically {@code MongoRecordStore}.
     * @param logger        plugin logger for warnings + retry diagnostics.
     */
    public AsyncRecorder(long warnThreshold, RecordStore store, Logger logger) {
        this(warnThreshold, store, new WalDurability(null, false, logger), logger);
    }

    /**
     * Constructor with explicit {@link WalDurability}. When the WAL is
     * enabled, the drain thread writes each batch to disk + fsyncs
     * before pushing to the database, then deletes the file after a
     * successful save. Crash recovery on next startup replays any
     * leftover files via {@link WalDurability#recover()}.
     */
    public AsyncRecorder(long warnThreshold, RecordStore store, WalDurability wal, Logger logger) {
        this.warnThreshold = warnThreshold;
        this.store = store;
        this.wal = wal;
        this.logger = logger;
        Thread.ofVirtual()
                .name("spyglass-drain")
                .start(this::drainLoop);
    }

    /**
     * Install a hook fired immediately after every successful intake.
     * The plugin uses this to publish {@code RecordCommittedEvent} to
     * Bukkit listeners without coupling AsyncRecorder to Bukkit (so
     * unit tests can run headless).
     *
     * <p>Hook executes on the calling thread and must be fast and
     * non-blocking — it sits on the listener hot path. Throwing here
     * does NOT drop the record (it's already queued); the exception
     * is logged and swallowed.
     */
    public void onCommitted(Consumer<EventRecord> hook) {
        this.committedHook = hook == null ? r -> {} : hook;
    }

    @Override
    public void record(EventRecord record) {
        // offer() on an unbounded LinkedBlockingDeque only returns false
        // on OutOfMemoryError; we deliberately do not bound the queue.
        // Losing an event at intake is the cost v2 explicitly refuses
        // to pay — same contract as v1. Heap pressure becomes the
        // operator's early-warning signal, surfaced via warnThreshold.
        queue.offer(record);
        try {
            committedHook.accept(record);
        } catch (RuntimeException hookFailure) {
            logger.warning("Spyglass committed-hook threw: " + hookFailure);
        }
        warnIfQueueDeep();
    }

    @Override
    public void recordAll(List<EventRecord> records) {
        if (records.isEmpty()) {
            return;
        }
        // Bulk intake for synthesized records (the rollback audit
        // trail). Deliberately skips the per-record committed hook:
        // firing one Bukkit RecordCommittedEvent per rolled block on
        // the main thread would cost more than the rollback that
        // produced them, and no reactive integration needs a
        // per-rolled-block notification. The DB save path is identical
        // — these records drain and persist like any other.
        for (EventRecord record : records) {
            queue.offer(record);
        }
        warnIfQueueDeep();
    }

    // Fire once when we first cross the threshold, and again at each
    // depth doubling past that, so a sustained outage produces a
    // visible growth trail in the log without flooding it. atomic-CAS
    // on lastWarnedDepth so concurrent callers don't duplicate the same
    // warning. Not a ceiling — the queue stays unbounded.
    private void warnIfQueueDeep() {
        int depth = queue.size();
        if (depth > warnThreshold) {
            long last = lastWarnedDepth.get();
            boolean firstCrossing = last == 0 && depth >= warnThreshold;
            boolean doubledSinceLast = last > 0 && depth >= last * 2;
            if ((firstCrossing || doubledSinceLast)
                    && lastWarnedDepth.compareAndSet(last, depth)) {
                logger.warning("Spyglass recorder queue depth " + depth
                        + " (warn threshold " + warnThreshold + "). No records dropped"
                        + " — queue is unbounded — but heap pressure grows with depth."
                        + " Check Mongo reachability and drain latency.");
            }
        }
    }

    @Override
    public boolean flush(Duration timeout) {
        // Snapshot semantics: capture the high-water mark at call time
        // and wait until the drain catches up to it. New records added
        // after this point may or may not also be drained — they don't
        // gate the call. Reading {@link #drained} and {@code queue.size()}
        // in this order can undercount the mark by at most one batch (a
        // record moves from queue to drained between reads) which
        // doesn't affect correctness; the drain catches it on the next
        // cycle either way.
        long deadlineNanos = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(Math.max(0L, timeout.seconds()));
        long highWaterMark = drained.get() + queue.size();
        while (drained.get() < highWaterMark) {
            if (System.nanoTime() >= deadlineNanos) {
                return false;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    @Override
    public ShutdownReport shutdown(Duration timeout) {
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeout.seconds());
        running.set(false);
        try {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos > 0) {
                stopped.await(remainingNanos, TimeUnit.NANOSECONDS);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        flushRemaining(deadlineNanos);
        return new ShutdownReport(drained.get(), dropped.get(), queue.size());
    }

    private void drainLoop() {
        int consecutiveFailures = 0;
        try {
            while (running.get() || !queue.isEmpty()) {
                EventRecord first = queue.poll(250, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                // Drain batch sized for ClickHouse's MergeTree, not Mongo.
                // Mongo absorbs 512-doc batches in ~ms; ClickHouse pays the
                // same fixed cost for any batch under ~10 k rows (one part
                // per INSERT, then a merge), so small batches starve the
                // ingest rate. With this larger batch, 1M-row WE bursts
                // drain at ~50-100k records/sec instead of ~7k/sec, which
                // is the difference between rollback waiting on a partial
                // CH and rollback seeing the whole burst.
                List<EventRecord> batch = new ArrayList<>();
                batch.add(first);
                queue.drainTo(batch, 9_999);

                // WAL durability: when enabled, fsync the batch to disk
                // before the DB push so a hard crash leaves a recoverable
                // file behind. write() is a no-op + null when WAL is
                // disabled, which keeps the RAM-only path identical to v1.
                java.nio.file.Path walFile = null;
                try {
                    walFile = wal.write(batch);
                } catch (java.io.IOException walFailure) {
                    logger.warning("Spyglass WAL write failed (" + walFailure.getMessage()
                            + "); proceeding with DB save anyway. Records still durable iff DB save succeeds.");
                }

                // Persist with retry + exponential backoff. A transient store
                // failure (Mongo hiccup, replica-set election, network blip)
                // must not kill the drain thread and silently drop every
                // subsequent record. We retry the same batch until either it
                // succeeds or shutdown is requested, then loop back to polling.
                while (true) {
                    try {
                        store.save(batch);
                        wal.ack(walFile);
                        drained.addAndGet(batch.size());
                        consecutiveFailures = 0;
                        break;
                    } catch (RuntimeException saveFailure) {
                        consecutiveFailures++;
                        long backoffMs = Math.min(30_000L,
                                250L << Math.min(consecutiveFailures - 1, 7));
                        if (consecutiveFailures == 1 || consecutiveFailures % 10 == 0) {
                            logger.warning("Spyglass recorder save failed ("
                                    + consecutiveFailures + "x, retry in "
                                    + backoffMs + "ms): " + saveFailure.getMessage());
                        }
                        if (!running.get()) {
                            logger.warning("Recorder shutting down mid-retry; re-queueing "
                                    + batch.size() + " records for final flush.");
                            // Unbounded queue -> offerFirst always succeeds.
                            for (int i = batch.size() - 1; i >= 0; i--) {
                                queue.offerFirst(batch.get(i));
                            }
                            return;
                        }
                        Thread.sleep(backoffMs);
                    }
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            stopped.countDown();
        }
    }

    /**
     * Best-effort save of any records still queued at shutdown. Retries
     * with exponential backoff against the shutdown deadline — so the
     * total wall time spent here is bounded by the caller's timeout,
     * not by a fixed retry count. Under normal Mongo availability this
     * succeeds on the first attempt. If Mongo is down and stays down
     * through the whole flush window, records are counted into
     * {@link #dropped} and logged at SEVERE.
     */
    private void flushRemaining(long deadlineNanos) {
        List<EventRecord> batch = new ArrayList<>();
        queue.drainTo(batch);
        if (batch.isEmpty()) {
            return;
        }
        // Mirror the drain loop's WAL contract on the shutdown path:
        // fsync the batch first so a crashed-shutdown leaves the
        // records recoverable on next startup.
        java.nio.file.Path walFile = null;
        try {
            walFile = wal.write(batch);
        } catch (java.io.IOException walFailure) {
            logger.warning("Spyglass WAL write failed at shutdown ("
                    + walFailure.getMessage() + "); proceeding with DB save anyway.");
        }
        int attempt = 0;
        while (true) {
            try {
                store.save(batch);
                wal.ack(walFile);
                drained.addAndGet(batch.size());
                return;
            } catch (RuntimeException ex) {
                attempt++;
                long backoffMs = Math.min(2_000L, 100L << Math.min(attempt - 1, 4));
                logger.warning("Recorder shutdown flush failed (attempt " + attempt
                        + ", retry in " + backoffMs + "ms): " + ex.getMessage());
                long untilDeadlineMs = Math.max(0L,
                        (deadlineNanos - System.nanoTime()) / 1_000_000L);
                if (untilDeadlineMs <= backoffMs) {
                    break;
                }
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        // Only reached when the database was unreachable for the entire
        // shutdown window. With WAL enabled, the batch is already on disk
        // and will be replayed automatically on next startup — no
        // operator action required. With WAL disabled, the records are
        // genuinely lost.
        if (wal.enabled()) {
            logger.severe("Recorder shutdown flush gave up within deadline; "
                    + batch.size() + " records left on the WAL and will be "
                    + "replayed on next startup once the database is reachable.");
        } else {
            logger.severe("Recorder shutdown flush gave up within deadline; "
                    + batch.size() + " records could not be persisted and are lost. "
                    + "Database was unreachable through the full flush-timeout. "
                    + "Set storage.durability = \"wal-batched\" to make this recoverable.");
            dropped.addAndGet(batch.size());
        }
    }

    public record ShutdownReport(long drained, long dropped, long remaining) {
    }
}
