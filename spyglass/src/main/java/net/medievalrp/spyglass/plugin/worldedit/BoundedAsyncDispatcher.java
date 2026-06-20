package net.medievalrp.spyglass.plugin.worldedit;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import org.jetbrains.annotations.ApiStatus;

/**
 * Caps how many WorldEdit record-build tasks run off-thread at once, and
 * applies backpressure when that cap is hit by running the task <b>inline</b>
 * on the calling thread.
 *
 * <h2>Why</h2>
 *
 * Vanilla WorldEdit's {@code setBlock} loop runs on the main thread and hands
 * 50k-cell batches to an <em>unbounded</em> virtual-thread executor. Each build
 * task constructs (and, when the queue is full, spills) its whole batch of
 * records in memory. On a slow store the spill encode is the slow step, so an
 * unbounded pool could spawn build tasks faster than they finish and pile their
 * batches across the pool — the queue ceiling caps the queue but not this stage
 * (#121). Bounding the in-flight build count is the matching cap.
 *
 * <p>Capping in-flight builds to {@code maxInFlight} bounds the records alive in
 * the build stage to {@code (maxInFlight + 1) x batch} — a few hundred MB,
 * independent of operation size. When all slots are busy the edit builds inline,
 * which paces the main-thread edit to the rate the build/spill stage can keep up
 * with: a TPS dip during a giant operation, but never a dropped record. With the
 * queue cap + spill this makes Spyglass's own footprint bounded for any op; it
 * does not (and cannot) bound Minecraft's own per-block cost for a huge edit.
 *
 * <p>Thread-safe; one instance is shared across all concurrent edits so the
 * bound is global, not per-edit.
 */
@ApiStatus.Internal
final class BoundedAsyncDispatcher {

    private final Executor executor;
    private final Semaphore permits;

    BoundedAsyncDispatcher(Executor executor, int maxInFlight) {
        if (maxInFlight < 1) {
            throw new IllegalArgumentException("maxInFlight must be >= 1, was " + maxInFlight);
        }
        this.executor = executor;
        this.permits = new Semaphore(maxInFlight);
    }

    /**
     * Run {@code task} on the executor if an in-flight slot is free (releasing
     * the slot when it completes, success or failure); otherwise run it inline
     * on the calling thread. If the executor rejects the task (shut down), run
     * it inline too, so no work is dropped.
     */
    void dispatch(Runnable task) {
        if (!permits.tryAcquire()) {
            // Saturated: build inline on the caller. This is the backpressure —
            // the producing thread pays the build/spill cost itself, so it can't
            // outrun persistence into an OOM.
            task.run();
            return;
        }
        boolean submitted = false;
        try {
            executor.execute(() -> {
                try {
                    task.run();
                } finally {
                    permits.release();
                }
            });
            submitted = true;
        } catch (RejectedExecutionException rejected) {
            // Executor shut down (server stopping): run inline so nothing drops.
            task.run();
        } finally {
            if (!submitted) {
                permits.release();
            }
        }
    }

    /** Free in-flight slots — for tests/diagnostics. */
    int availableSlots() {
        return permits.availablePermits();
    }
}
