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

// FIFO queue of rollback/restore jobs. One runs at a time;
// concurrent commands enqueue and wait. In-memory only; pending and
// recent are wiped by restart. Crash-resume of an interrupted job is
// handled by RollbackResumeStore.
//
// Thread-safety: a single ReentrantLock guards state transitions and
// queue mutations. The engine reads the per-job cancelFlag off-lock
// so the apply loop doesn't block.
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

    // Wired at plugin startup; the queue and service have a circular
    // ref so the runner gets set after both are constructed. The
    // runner is invoked under the lock, so it must kick off async
    // work and return quickly, and it must eventually call finish().
    public void setRunner(Consumer<RollbackJob> runner) {
        this.runner = runner;
    }

    // Returns 0 if the job is running immediately, otherwise the
    // queue position.
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

    public void finish(RollbackJob job, RollbackJob.State terminalState) {
        lock.lock();
        try {
            if (inFlight != job) {
                // Job was cancelled while still pending; just file it.
                job.state = terminalState;
                if (job.endTime == null) job.endTime = Instant.now();
                pushRecent(job);
                return;
            }
            inFlight = null;
            job.state = terminalState;
            if (job.endTime == null) job.endTime = Instant.now();
            pushRecent(job);
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

    // Pending jobs are removed and marked CANCELLED. In-flight jobs
    // get a flag the engine reads at the next chunk boundary.
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
