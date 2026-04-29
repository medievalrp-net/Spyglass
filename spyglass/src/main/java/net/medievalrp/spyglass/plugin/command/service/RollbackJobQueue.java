package net.medievalrp.spyglass.plugin.command.service;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * FIFO queue of rollback / restore jobs. One job runs at a time —
 * concurrent {@code /sg rollback} commands enqueue and wait their
 * turn. Provides cancellation (pending or in-flight) and a recent-
 * history ring buffer for {@code /sg rbqueue} visibility.
 *
 * <p>The queue is in-memory only — restart wipes pending and recent.
 * Crash-resume of an in-flight job is handled separately via
 * {@code RollbackResumeStore} (writes the in-flight job's query to
 * disk so a restart can offer to re-run it).
 *
 * <p>Thread-safety: all methods are guarded by a single
 * {@link ReentrantLock}. State transitions ({@code start, finish,
 * cancel}) and queue mutations need the lock; the per-job
 * {@code AtomicBoolean cancelFlag} is read by the engine off-lock to
 * avoid blocking the apply loop.
 */
@ApiStatus.Internal
public final class RollbackJobQueue {

    private static final int RECENT_KEEP = 10;

    private final ReentrantLock lock = new ReentrantLock();
    private final Deque<RollbackJob> pending = new ArrayDeque<>();
    private final Deque<RollbackJob> recent = new ArrayDeque<>(RECENT_KEEP);
    private @Nullable RollbackJob inFlight;
    private @Nullable Consumer<RollbackJob> runner;

    public RollbackJobQueue() {
    }

    /**
     * Wire the runner that gets called when a job is ready to start.
     * Called once at plugin startup after the {@link RollbackService}
     * exists (the queue and the service have a circular reference —
     * service submits jobs to queue, queue calls service to run
     * them — so the runner is set after both are constructed).
     *
     * <p>Invoked under the queue's internal lock — the runner should
     * kick off async work and return quickly. The runner MUST
     * eventually call {@link #finish} on the job so the queue can
     * dispatch the next pending one.
     */
    public void setRunner(Consumer<RollbackJob> runner) {
        this.runner = runner;
    }

    /** Submit a new job. Starts immediately if no other is in flight,
     *  otherwise queues. Returns the queue position (0 = running,
     *  >0 = N-th in line) so the operator can be told. */
    public int submit(RollbackJob job) {
        if (runner == null) {
            throw new IllegalStateException(
                    "RollbackJobQueue.setRunner must be called at startup before submit");
        }
        lock.lock();
        try {
            if (inFlight == null) {
                inFlight = job;
                job.state = RollbackJob.State.RUNNING;
                job.startTime = Instant.now();
                runner.accept(job);
                return 0;
            }
            pending.add(job);
            return pending.size();
        } finally {
            lock.unlock();
        }
    }

    /** Called by the runner when a job finishes (any terminal state).
     *  Moves the job to recent, dispatches the next pending job if
     *  any. */
    public void finish(RollbackJob job, RollbackJob.State terminalState) {
        lock.lock();
        try {
            if (inFlight != job) {
                // Out-of-band finish (e.g. cancelled while still
                // pending). No-op for the in-flight slot; just file
                // it away in recent.
                job.state = terminalState;
                if (job.endTime == null) job.endTime = Instant.now();
                pushRecent(job);
                return;
            }
            inFlight = null;
            job.state = terminalState;
            if (job.endTime == null) job.endTime = Instant.now();
            pushRecent(job);
            // Drain to next pending
            RollbackJob next = pending.pollFirst();
            if (next != null && runner != null) {
                inFlight = next;
                next.state = RollbackJob.State.RUNNING;
                next.startTime = Instant.now();
                runner.accept(next);
            }
        } finally {
            lock.unlock();
        }
    }

    /** Cancel a job by id. If pending, removes from the queue and
     *  marks CANCELLED. If in-flight, sets the cancel flag — the
     *  engine sees it at the next chunk boundary and stops. Returns
     *  the affected job, or empty if no match. */
    public Optional<RollbackJob> cancel(UUID jobId) {
        lock.lock();
        try {
            if (inFlight != null && inFlight.id.equals(jobId)) {
                inFlight.cancelFlag.set(true);
                return Optional.of(inFlight);
            }
            for (RollbackJob job : pending) {
                if (job.id.equals(jobId)) {
                    pending.remove(job);
                    job.state = RollbackJob.State.CANCELLED;
                    job.endTime = Instant.now();
                    pushRecent(job);
                    return Optional.of(job);
                }
            }
            return Optional.empty();
        } finally {
            lock.unlock();
        }
    }

    /** Convenience: cancel the in-flight job (the most common
     *  "stop the rollback" action). */
    public Optional<RollbackJob> cancelInFlight() {
        lock.lock();
        try {
            if (inFlight == null) return Optional.empty();
            inFlight.cancelFlag.set(true);
            return Optional.of(inFlight);
        } finally {
            lock.unlock();
        }
    }

    /** Snapshot the current queue state for {@code /sg rbqueue list}. */
    public Snapshot snapshot() {
        lock.lock();
        try {
            return new Snapshot(
                    inFlight,
                    new ArrayList<>(pending),
                    new ArrayList<>(recent));
        } finally {
            lock.unlock();
        }
    }

    private void pushRecent(RollbackJob job) {
        recent.addFirst(job);
        while (recent.size() > RECENT_KEEP) {
            recent.removeLast();
        }
    }

    public record Snapshot(@Nullable RollbackJob inFlight,
                           List<RollbackJob> pending,
                           List<RollbackJob> recent) {
    }
}
