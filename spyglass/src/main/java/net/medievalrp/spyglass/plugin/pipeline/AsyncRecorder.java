package net.medievalrp.spyglass.plugin.pipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
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
 * <h2>Bounded queue with off-main backpressure</h2>
 *
 * The queue is bounded by {@code queueMax} (the {@code storage.queue-max}
 * config). When an <b>off-main</b> producer — the WorldEdit build stage
 * (via {@link #recordAll}) or FAWE (via {@link #record} on its own worker
 * threads) — would enqueue while the queue is already at the ceiling, it
 * <b>blocks</b> until the drain frees space. This paces the bulk-edit
 * firehoses to the drain so a multi-million-block paste can never grow the
 * queue past the cap into an {@link OutOfMemoryError}. Nothing is dropped
 * at intake: a parked producer resumes the instant space opens.
 *
 * <p>The Bukkit primary thread is <b>never</b> blocked. Its per-event
 * listeners record at a low rate and must not stall a tick, so they are
 * always admitted even at the ceiling — a small, bounded overshoot that
 * keeps the v1 no-drop guarantee for the main-thread audit path. Set
 * {@code queueMax <= 0} ({@code storage.queue-max = 0}) to restore the
 * legacy fully-unbounded queue.
 *
 * <p>{@code warnThreshold} ({@code storage.queue-capacity}) is a softer,
 * lower signal that sits below the ceiling: crossing it logs a warning so
 * operators notice drain lag early, before backpressure engages. Warnings
 * fire at the first crossing and at doubling intervals thereafter, so a
 * genuine outage surfaces its growth shape in the log without flooding it.
 *
 * <h2>Overflow goes to disk, not the heap</h2>
 *
 * Bounding the queue alone doesn't bound vanilla WorldEdit: its
 * {@code setBlock} loop runs on the main thread, so overflow we refuse to
 * block would pile up in the off-main build threads instead. When a
 * {@link SpillBuffer} is wired, {@link #recordAll} writes the over-ceiling
 * overflow to disk (fsynced) rather than RAM, and the drain replays those
 * segments — so an uncappable paste stays heap-flat and loses nothing. The
 * spilled segments also survive a hard crash and are replayed on next start.
 *
 * <h2>The scenarios where records can still be lost</h2>
 *
 * Under normal operation (server running, store reachable) an event that
 * reaches {@link #record} always lands in the store. Two edge cases remain:
 *
 * <ol>
 *   <li><b>Hard JVM death</b> — server crash, SIGKILL, power loss. The
 *       in-RAM queue (capped at {@code queueMax}) is lost with the process;
 *       {@code wal-batched} durability closes that window, and spilled
 *       overflow is already on disk and recovered on next start.</li>
 *   <li><b>Shutdown flush deadline exhaustion</b> —
 *       {@link #flushRemaining} retries with backoff for the full
 *       configured {@code flush-timeout}. If the store is still unreachable
 *       when the deadline expires, undrained queue records are counted into
 *       {@link ShutdownReport#dropped} and logged at SEVERE (spilled
 *       segments stay on disk for next-start recovery). Under normal store
 *       availability this code path is unreachable.</li>
 * </ol>
 *
 * <p>Aside from those, records are durable once {@link #record} returns.
 */
@ApiStatus.Internal
public final class AsyncRecorder implements Recorder {

    private final LinkedBlockingDeque<EventRecord> queue = new LinkedBlockingDeque<>();
    private final long warnThreshold;
    // Hard queue ceiling that off-main producers backpressure against;
    // <= 0 means unbounded (legacy behaviour). See awaitCapacityIfBlockable.
    private final long queueMax;
    // Reports whether the calling thread is the Bukkit server thread, which
    // is never blocked by the ceiling. Bukkit::isPrimaryThread in production.
    private final BooleanSupplier primaryThread;
    private final RecordStore store;
    private final WalDurability wal;
    // On-disk overflow buffer. When the queue is at queueMax, the uncappable
    // vanilla-WorldEdit firehose (recordAll) spills here instead of holding
    // overflow in RAM; the drain replays it. Disabled (no-op) by default.
    private final SpillBuffer spill;
    private final Logger logger;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final CountDownLatch stopped = new CountDownLatch(1);
    private final AtomicLong drained = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong lastWarnedDepth = new AtomicLong();
    // Backpressure gate: off-main producers park on spaceMonitor when the
    // queue is at queueMax; the drain notifies after it frees space. The
    // waiter count keeps the drain's wake check lock-free (a plain read)
    // whenever nobody is parked, which is the common case.
    private final Object spaceMonitor = new Object();
    private final AtomicLong backpressureWaiters = new AtomicLong();
    // Count of spill-write failures (disk full / I/O), for rate-limited logging.
    private final AtomicLong spillFailures = new AtomicLong();
    // Consecutive store-save failures, for retry backoff. Touched only by the
    // single drain thread, so it needs no synchronization.
    private int consecutiveFailures = 0;
    private volatile Consumer<EventRecord> committedHook = r -> {};
    private volatile FlushBarrier flushBarrier = timeout -> true;
    // Opt-in analytics counter (#168). Null unless analytics.enabled, so the
    // hot path is a single volatile read + null check when off - the same shape
    // as committedHook above. Set once at startup via setIngestStats.
    private volatile IngestStats ingestStats = null;

    /**
     * Pre-flush gate. {@link #flush} awaits this before snapshotting the
     * queue high-water mark, so a deferred-serialization stage that feeds
     * {@link #record} off-thread (see {@code DeferredSerializer}, #98) can
     * be drained first — otherwise a rollback right after a burst would
     * snapshot the queue before the in-flight records reach it.
     */
    @FunctionalInterface
    public interface FlushBarrier {
        /** @return {@code true} if the stage drained within {@code timeout}. */
        boolean awaitQuiescent(Duration timeout);
    }

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
        this(warnThreshold, 0L, store, new WalDurability(null, false, logger), () -> false, logger);
    }

    /**
     * Constructor with explicit {@link WalDurability}. When the WAL is
     * enabled, the drain thread writes each batch to disk + fsyncs
     * before pushing to the database, then deletes the file after a
     * successful save. Crash recovery on next startup replays any
     * leftover files via {@link WalDurability#recover()}.
     *
     * <p>Leaves the queue unbounded ({@code queueMax = 0}) and treats no
     * thread as primary — the shape unit tests want headless.
     */
    public AsyncRecorder(long warnThreshold, RecordStore store, WalDurability wal, Logger logger) {
        this(warnThreshold, 0L, store, wal, () -> false, logger);
    }

    /**
     * Full constructor. {@code queueMax} is the hard queue ceiling that
     * off-main producers backpressure against (≤ 0 = unbounded);
     * {@code primaryThread} reports whether the calling thread is the
     * Bukkit server thread, which is never blocked by the ceiling. The
     * plugin passes {@code Bukkit::isPrimaryThread}; headless tests pass a
     * controllable fake.
     */
    public AsyncRecorder(long warnThreshold, long queueMax, RecordStore store,
                         WalDurability wal, BooleanSupplier primaryThread, Logger logger) {
        this(warnThreshold, queueMax, store, wal,
                new SpillBuffer(null, false, logger), primaryThread, logger);
    }

    /**
     * Full constructor with on-disk overflow. {@code spill} absorbs the
     * bulk-edit firehose's overflow when the queue hits {@code queueMax}, so a
     * paste the operator can't cap stays heap-flat without dropping records.
     * Pass a disabled {@link SpillBuffer} ({@code new SpillBuffer(null, false,
     * logger)}) to keep everything in RAM — the shape headless tests want.
     */
    public AsyncRecorder(long warnThreshold, long queueMax, RecordStore store,
                         WalDurability wal, SpillBuffer spill,
                         BooleanSupplier primaryThread, Logger logger) {
        this.warnThreshold = warnThreshold;
        this.queueMax = queueMax;
        this.store = store;
        this.wal = wal;
        this.spill = spill;
        this.primaryThread = primaryThread;
        this.logger = logger;
        // Dedicated platform thread, not a virtual thread. The drain is a
        // single perpetual consumer loop that blocks on store.save() I/O;
        // virtual threads pay off for many short-lived blocking tasks, not
        // one long-lived loop. As a virtual thread, every record's wakeup
        // paid a ForkJoinPool continuation-submission (unpark ->
        // submitRunContinuation -> FJP.execute) that showed up on the
        // ingest hot path in the timings profile, with nothing to free the
        // carrier for (there is only one drainer). A platform thread is
        // unparked directly and also sidesteps carrier pinning if the DB
        // driver synchronizes internally during save(). Daemon so it never
        // holds JVM exit; shutdown() still drains/flushes via the latch.
        Thread.ofPlatform()
                .daemon(true)
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

    /**
     * Install a barrier that {@link #flush} drains before waiting on the
     * queue. Used to make flush await an off-thread serialization stage
     * (rollbackable records mid-serialization) so read-your-writes holds
     * for a rollback issued right after a burst (#98). Default is a no-op,
     * so setups without a deferred stage (and unit tests) are unaffected.
     */
    public void setFlushBarrier(FlushBarrier barrier) {
        this.flushBarrier = barrier == null ? (timeout -> true) : barrier;
    }

    /**
     * Install the opt-in analytics counter (#168). When set, every intake is
     * tallied by event type. Null (the default) leaves the hot path a single
     * volatile read + null check. Set once at startup, after WAL replay so
     * recovered records don't count as live ingest.
     */
    public void setIngestStats(IngestStats stats) {
        this.ingestStats = stats;
    }

    /** Live recorder queue depth (analytics gauge, #168). O(1) counter read. */
    public int queueDepth() {
        return queue.size();
    }

    /** Cumulative records persisted to the store (analytics gauge, #168). */
    public long drainedCount() {
        return drained.get();
    }

    /** Cumulative records lost to shutdown-flush exhaustion (analytics gauge, #168). */
    public long droppedCount() {
        return dropped.get();
    }

    @Override
    public void record(EventRecord record) {
        // Backpressure an off-main firehose (FAWE worker threads reach the
        // pipeline here) when the queue is at the ceiling; the main thread
        // and an unbounded queue both fall straight through. offer() on a
        // LinkedBlockingDeque only returns false on OutOfMemoryError, so once
        // the gate guarantees room the record always lands.
        awaitCapacityIfBlockable();
        queue.offer(record);
        // Analytics intake count (#168): null unless analytics.enabled.
        IngestStats stats = ingestStats;
        if (stats != null) {
            stats.onRecord(record.event());
        }
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
        // Analytics intake count (#168): tally every record in the bulk batch
        // (WorldEdit/FAWE/rollback-audit) before it queues or spills, so the
        // analytics view shows bulk-edit load too. Null unless analytics.enabled.
        IngestStats stats = ingestStats;
        if (stats != null) {
            for (EventRecord record : records) {
                stats.onRecord(record.event());
            }
        }
        // Bulk intake for the WorldEdit build stage and the rollback audit
        // trail. The WorldEdit path is the firehose this whole ceiling exists
        // for, and it runs off-main.
        //
        // When the queue is at the ceiling, spill this batch to disk instead
        // of holding it in RAM. Spilling keeps the heap flat (overflow lives on
        // disk) and loses nothing: the drain replays spilled segments. The drain
        // also frees queue space continuously, so as soon as it keeps up the
        // batches flow back through the fast in-RAM path.
        //
        // Spill on ANY thread, including the main thread: under #121 backpressure
        // a huge WorldEdit op builds inline on the editing (main) thread once the
        // off-thread build pool is saturated, and that inline build must spill to
        // disk too — not fall through to the main-thread always-admit path, which
        // would grow the queue unbounded again. The bulk recordAll callers are
        // the WE firehose and the (tiny, synthesized) rollback audit, so spilling
        // regardless of thread is correct for both.
        if (spill.enabled() && queueMax > 0 && queue.size() >= queueMax) {
            try {
                spill.spill(records);
                return;
            } catch (java.io.IOException spillFailure) {
                // Disk full / I/O error: fall back to in-RAM backpressure
                // (park) rather than drop — no-loss is the chosen contract.
                long n = spillFailures.incrementAndGet();
                if (n == 1 || n % 1000 == 0) {
                    logger.warning("Spyglass spill write failed (" + n + "x), falling back to"
                            + " in-RAM backpressure: " + spillFailure.getMessage());
                }
            }
        }
        // No spill (disabled, unbounded, or below the ceiling): gate once
        // before the batch — an off-main producer parks until there is room;
        // the primary thread and an unbounded queue fall straight through.
        // Deliberately skips the per-record committed hook: firing one Bukkit
        // RecordCommittedEvent per rolled/edited block on the main thread would
        // cost more than the op that produced them, and no reactive
        // integration needs a per-block notification.
        awaitCapacityIfBlockable();
        for (EventRecord record : records) {
            queue.offer(record);
        }
        warnIfQueueDeep();
    }

    // Fire once when we first cross the threshold, and again at each
    // depth doubling past that, so a sustained outage produces a
    // visible growth trail in the log without flooding it. atomic-CAS
    // on lastWarnedDepth so concurrent callers don't duplicate the same
    // warning. Soft signal below the ceiling, not the ceiling itself.
    private void warnIfQueueDeep() {
        int depth = queue.size();
        if (depth > warnThreshold) {
            long last = lastWarnedDepth.get();
            boolean firstCrossing = last == 0 && depth >= warnThreshold;
            boolean doubledSinceLast = last > 0 && depth >= last * 2;
            if ((firstCrossing || doubledSinceLast)
                    && lastWarnedDepth.compareAndSet(last, depth)) {
                String ceiling = queueMax > 0
                        ? "; off-main edits backpressure at the queue-max ceiling of " + queueMax
                        : ", and the queue is unbounded so heap pressure grows with depth";
                logger.warning("Spyglass recorder queue depth " + depth
                        + " (warn threshold " + warnThreshold + "). The drain is"
                        + " falling behind" + ceiling + ". No records dropped — check the"
                        + " storage backend's reachability and drain latency.");
            }
        }
    }

    /**
     * Backpressure gate for the bulk-edit firehoses. When {@link #queueMax}
     * is set and an <i>off-main</i> producer (the WorldEdit build stage via
     * {@link #recordAll}, or FAWE via {@link #record} on its worker threads)
     * is about to enqueue while the queue is already at the ceiling, it parks
     * here until the drain frees space — so a multi-million-block paste can
     * never grow the queue past the cap into an {@link OutOfMemoryError}.
     * Nothing is dropped: the producer is paced, not rejected.
     *
     * <p>The Bukkit primary thread is never parked. Its per-event listeners
     * are low-rate and must not stall a tick, so they are always admitted —
     * a small bounded overshoot that preserves the no-drop guarantee for the
     * main-thread audit path. With {@code queueMax <= 0} this is a no-op.
     */
    private void awaitCapacityIfBlockable() {
        // Fast path: unbounded, room to spare, or the primary thread (never
        // parked). queue.size() is an O(1) counter read on a deque.
        if (queueMax <= 0 || queue.size() < queueMax || primaryThread.getAsBoolean()) {
            return;
        }
        backpressureWaiters.incrementAndGet();
        try {
            synchronized (spaceMonitor) {
                // Re-check under the monitor. The 50 ms timeout is a
                // belt-and-suspenders backstop so a missed notify still
                // resolves promptly; running == false (shutdown) releases the
                // producer so the final flush isn't blocked behind it.
                while (running.get() && queue.size() >= queueMax) {
                    try {
                        spaceMonitor.wait(50L);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        } finally {
            backpressureWaiters.decrementAndGet();
        }
    }

    // Wake any producers parked on the backpressure gate. Called after the
    // drain frees space and at shutdown. The waiter-count guard keeps this a
    // single volatile read (no monitor) whenever nobody is parked.
    private void wakeBackpressureWaiters() {
        if (backpressureWaiters.get() > 0) {
            synchronized (spaceMonitor) {
                spaceMonitor.notifyAll();
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
        // Drain the deferred-serialization stage first (#98): records still
        // mid-serialization must be handed to the queue before we snapshot
        // the high-water mark, or a rollback right after a burst would miss
        // them and partially restore. The barrier is given the full timeout
        // budget; the queue wait below is still bounded by the same overall
        // deadline, so a barrier that consumes the budget surfaces as a
        // flush timeout rather than an unbounded wait.
        if (!flushBarrier.awaitQuiescent(timeout)) {
            return false;
        }
        // Include spilled overflow in the mark: a rollback issued right after a
        // paste must wait for records sitting in the disk spill too, not just
        // the in-RAM queue, or it would read before they reach the store.
        long highWaterMark = drained.get() + queue.size() + spill.pendingRecordCount();
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
        // Release any producer parked on the backpressure gate so it admits
        // its record and the final flush can account for it, instead of
        // hanging behind a full queue while we tear down.
        wakeBackpressureWaiters();
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
        try {
            while (running.get() || !queue.isEmpty() || spill.hasPending()) {
                // Hot path first: drain the in-RAM queue non-blocking so, while
                // a burst is live, batches keep cycling through RAM and the
                // freed space lets producers stay off the (slower) spill path.
                EventRecord first = queue.poll();
                if (first != null) {
                    if (!drainQueueBatch(first)) {
                        return; // shutdown mid-retry; batch re-queued for the flush
                    }
                    continue;
                }
                // Queue empty: replay one spilled overflow segment (older than
                // anything still arriving). Reclaims disk + honours the flush
                // high-water mark. One segment in RAM at a time keeps heap flat.
                if (spill.hasPending()) {
                    SpillBuffer.Spilled spilled = spill.poll();
                    if (spilled != null) {
                        if (!persistWithRetry(spilled.records())) {
                            // Shutdown mid-retry: leave the segment on disk; it
                            // replays on next startup. Don't ack (delete) it.
                            return;
                        }
                        spill.ack(spilled);
                        wakeBackpressureWaiters();
                        continue;
                    }
                    // hasPending() but nothing polled (all-corrupt or a
                    // counter/file skew): fall through to the blocking poll so
                    // we throttle instead of busy-spinning.
                }
                // Nothing in queue or spill: block briefly for new queue work
                // rather than spin.
                EventRecord waited = queue.poll(250, TimeUnit.MILLISECONDS);
                if (waited != null && !drainQueueBatch(waited)) {
                    return;
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            stopped.countDown();
        }
    }

    /**
     * Pull a drain batch starting at {@code first}, persist it, and on a
     * shutdown-mid-retry re-queue it for the final flush. Returns {@code false}
     * only in that shutdown case (the caller must stop the drain loop).
     */
    private boolean drainQueueBatch(EventRecord first) {
        // Drain batch sized for ClickHouse's MergeTree, not Mongo. Mongo
        // absorbs 512-doc batches in ~ms; ClickHouse pays the same fixed cost
        // for any batch under ~10 k rows (one part per INSERT, then a merge),
        // so small batches starve the ingest rate. With this larger batch,
        // 1M-row WE bursts drain at ~50-100k records/sec instead of ~7k/sec.
        List<EventRecord> batch = new ArrayList<>();
        batch.add(first);
        queue.drainTo(batch, 9_999);
        // Pulling the batch freed queue space; wake any producer parked on the
        // backpressure gate before the (possibly slow) save below.
        wakeBackpressureWaiters();
        if (!persistWithRetry(batch)) {
            logger.warning("Recorder shutting down mid-retry; re-queueing "
                    + batch.size() + " records for final flush.");
            for (int i = batch.size() - 1; i >= 0; i--) {
                queue.offerFirst(batch.get(i));
            }
            return false;
        }
        return true;
    }

    /**
     * Persist one batch with WAL fsync + retry/backoff. Retries the same batch
     * until it saves or shutdown is requested. Returns {@code true} once saved
     * (and {@link #drained} is incremented); {@code false} if shutdown
     * interrupts mid-retry, leaving it to the caller to re-queue (queue path)
     * or leave on disk (spill path). Runs only on the single drain thread, so
     * {@link #consecutiveFailures} needs no synchronization.
     */
    private boolean persistWithRetry(List<EventRecord> batch) {
        // WAL durability: when enabled, fsync the batch to disk before the DB
        // push so a hard crash leaves a recoverable file behind. write() is a
        // no-op + null when WAL is disabled, keeping the RAM-only path as v1.
        java.nio.file.Path walFile = null;
        try {
            walFile = wal.write(batch);
        } catch (java.io.IOException walFailure) {
            logger.warning("Spyglass WAL write failed (" + walFailure.getMessage()
                    + "); proceeding with DB save anyway. Records still durable iff DB save succeeds.");
        }
        // A transient store failure (Mongo hiccup, replica-set election,
        // network blip) must not kill the drain thread and silently drop every
        // subsequent record. Retry the same batch until it succeeds or shutdown.
        while (true) {
            try {
                store.save(batch);
                wal.ack(walFile);
                drained.addAndGet(batch.size());
                consecutiveFailures = 0;
                return true;
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
                    return false;
                }
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
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
