package net.medievalrp.spyglass.plugin.pipeline;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.logging.Logger;
import net.medievalrp.spyglass.api.util.Duration;
import org.jetbrains.annotations.ApiStatus;

/**
 * Off-main-thread serialization stage for listeners whose per-record cost
 * is dominated by item serialization (NBT -&gt; bytes -&gt; base64 plus
 * Adventure text extraction) or by the block-data string (#154). A listener
 * captures a cheap snapshot on the main thread and hands the heavy build to
 * {@link #execute}, which runs it on a worker and calls
 * {@code Recorder.record()} from there.
 *
 * <h2>Why a pooled executor (not virtual-thread-per-task)</h2>
 *
 * <p>The block break/place listeners (#154) submit a task <em>per event</em>
 * (thousands per second under load). A JFR profile showed that
 * {@code newVirtualThreadPerTaskExecutor} spawning a fresh virtual thread for
 * each of those events, plus a {@code ReentrantLock} acquisition on the
 * in-flight counter, cost more main-thread CPU than the cheap
 * {@code getAsString()} call they were deferring -- a net regression at high
 * event rates. This stage therefore uses a small <b>reused thread pool</b>
 * (no per-event thread creation) and a <b>lock-free</b> {@link AtomicLong}
 * in-flight counter, so the only main-thread cost per submission is one atomic
 * increment plus a queue offer. The pool's queue is unbounded, so
 * {@link #execute} never blocks the server thread; the block listeners only
 * fire on real gameplay block events (WorldEdit edits bypass Bukkit events),
 * so the queue is bounded by gameplay rate in practice.
 *
 * <h2>Flush coordination (rollbackable records)</h2>
 *
 * <p>Pickups are forensic-only, but this stage is also used for
 * <em>rollbackable</em> container and block records (#98, #154). Those sit in
 * front of the recorder queue, so a rollback's read-your-writes flush must
 * drain this stage <em>first</em>: {@link #awaitQuiescence} blocks until every
 * task submitted before the call has finished handing its record to the
 * recorder. Wire it as the recorder's pre-flush barrier
 * ({@link AsyncRecorder#setFlushBarrier}) so
 * {@code flush()} = drain this stage, then drain the queue.
 *
 * <h2>Durability tradeoff</h2>
 *
 * <p>A task in flight here has been snapshotted off the live world but not
 * yet enqueued, so it sits <b>outside</b> the recorder's no-drop/WAL
 * guarantee -- a hard JVM crash in that window (the few ms of serialization)
 * loses it. For pickups this is irrelevant (never rolled back); for
 * rollbackable records it is a small, deliberate weakening of rollback
 * completeness, accepted for the tick-latency win. {@code flush()} still
 * guarantees correctness for an <em>orderly</em> rollback -- only an unclean
 * crash mid-serialization can lose a deferred record.
 */
@ApiStatus.Internal
public final class DeferredSerializer implements Executor {

    private final ExecutorService executor;
    private final Logger logger;
    // Lock-free in-flight count: incremented on the main thread at submit,
    // decremented on the worker at completion. awaitQuiescence (rare, only on
    // a rollback flush) polls it, so the hot path never takes a lock.
    private final AtomicLong inFlight = new AtomicLong();

    public DeferredSerializer(Logger logger) {
        this(defaultPool(), logger);
    }

    /** Visible for tests: inject a deterministic executor (e.g. same-thread). */
    DeferredSerializer(ExecutorService executor, Logger logger) {
        this.executor = executor;
        this.logger = logger;
    }

    private static ExecutorService defaultPool() {
        int threads = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
        AtomicInteger idx = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "spyglass-deferred-serializer-" + idx.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newFixedThreadPool(threads, factory);
    }

    @Override
    public void execute(Runnable task) {
        inFlight.incrementAndGet();
        try {
            executor.execute(() -> run(task));
        } catch (RejectedExecutionException rejected) {
            // Executor already shutting down. Run inline so a rollbackable
            // record isn't silently dropped, then settle the counter.
            run(task);
        }
    }

    private void run(Runnable task) {
        try {
            task.run();
        } catch (RuntimeException ex) {
            // Structured logging only: a poison item that makes
            // serialization throw must not escape to the worker's default
            // handler (stderr). The record is skipped; the cause is logged.
            logger.warning("Spyglass deferred record serialization failed;"
                    + " record skipped: " + ex);
        } finally {
            inFlight.decrementAndGet();
        }
    }

    /**
     * Block until every task submitted before this call has finished (its
     * record handed to the recorder), or {@code timeout} elapses. Tasks
     * submitted concurrently during the wait may also be awaited; the
     * contract the rollback flush relies on is that nothing submitted
     * before the call is still in flight on success.
     *
     * <p>Implemented by polling the lock-free counter; the flush is rare
     * (only on a rollback), so a short poll interval costs nothing on the
     * hot path while keeping submit lock-free.
     *
     * @return {@code true} if the stage reached quiescence, {@code false} on timeout
     */
    public boolean awaitQuiescence(Duration timeout) {
        long deadlineNanos = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(Math.max(0L, timeout.seconds()));
        while (inFlight.get() > 0) {
            if (System.nanoTime() - deadlineNanos >= 0) {
                return false;
            }
            // 0.2 ms poll: a flush waiting on a few ms of serialization wakes
            // promptly without busy-spinning.
            LockSupport.parkNanos(200_000L);
        }
        return true;
    }

    /**
     * Stop accepting new tasks and wait up to {@code timeout} for in-flight
     * tasks to finish. Call before the recorder shuts down so deferred
     * records reach the queue before the drain stops.
     */
    public void shutdown(Duration timeout) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(Math.max(0L, timeout.seconds()), TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }
}
