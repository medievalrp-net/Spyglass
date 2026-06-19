package net.medievalrp.spyglass.plugin.pipeline;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;
import net.medievalrp.spyglass.api.util.Duration;
import org.jetbrains.annotations.ApiStatus;

/**
 * Off-main-thread serialization stage for listeners whose per-record cost
 * is dominated by item serialization (NBT -&gt; bytes -&gt; base64 plus
 * Adventure text extraction). A listener captures a cheap snapshot on the
 * main thread and hands the heavy build to {@link #execute}, which runs it
 * on a virtual thread and calls {@code Recorder.record()} from there.
 *
 * <h2>Flush coordination (rollbackable records)</h2>
 *
 * <p>Pickups are forensic-only, but this stage is also used for
 * <em>rollbackable</em> container records (#98). Those sit in front of the
 * recorder queue, so a rollback's read-your-writes flush must drain this
 * stage <em>first</em>: {@link #awaitQuiescence} blocks until every task
 * submitted before the call has finished handing its record to the
 * recorder. Wire it as the recorder's pre-flush barrier
 * ({@link AsyncRecorder#setFlushBarrier}) so
 * {@code flush()} = drain this stage, then drain the queue.
 *
 * <h2>Durability tradeoff</h2>
 *
 * <p>A task in flight here has been snapshotted off the live world but not
 * yet enqueued, so it sits <b>outside</b> the recorder's no-drop/WAL
 * guarantee — a hard JVM crash in that window (the few ms of
 * serialization) loses it. For pickups this is irrelevant (never rolled
 * back); for rollbackable container records it is a small, deliberate
 * weakening of rollback completeness, accepted for the tick-latency win
 * (#98). {@code flush()} still guarantees correctness for an
 * <em>orderly</em> rollback — only an unclean crash mid-serialization can
 * lose a deferred record.
 */
@ApiStatus.Internal
public final class DeferredSerializer implements Executor {

    private final ExecutorService executor;
    private final Logger logger;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition idle = lock.newCondition();
    private long inFlight;

    public DeferredSerializer(Logger logger) {
        this(Executors.newVirtualThreadPerTaskExecutor(), logger);
    }

    /** Visible for tests: inject a deterministic executor (e.g. same-thread). */
    DeferredSerializer(ExecutorService executor, Logger logger) {
        this.executor = executor;
        this.logger = logger;
    }

    @Override
    public void execute(Runnable task) {
        lock.lock();
        try {
            inFlight++;
        } finally {
            lock.unlock();
        }
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
            lock.lock();
            try {
                if (--inFlight == 0) {
                    idle.signalAll();
                }
            } finally {
                lock.unlock();
            }
        }
    }

    /**
     * Block until every task submitted before this call has finished (its
     * record handed to the recorder), or {@code timeout} elapses. Tasks
     * submitted concurrently during the wait may also be awaited; the
     * contract the rollback flush relies on is that nothing submitted
     * before the call is still in flight on success.
     *
     * @return {@code true} if the stage reached quiescence, {@code false} on timeout
     */
    public boolean awaitQuiescence(Duration timeout) {
        long deadlineNanos = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(Math.max(0L, timeout.seconds()));
        lock.lock();
        try {
            while (inFlight > 0) {
                long remaining = deadlineNanos - System.nanoTime();
                if (remaining <= 0) {
                    return false;
                }
                idle.awaitNanos(remaining);
            }
            return true;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            lock.unlock();
        }
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
