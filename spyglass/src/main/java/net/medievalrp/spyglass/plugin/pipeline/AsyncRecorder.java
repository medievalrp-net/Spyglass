package net.medievalrp.spyglass.plugin.pipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import net.medievalrp.spyglass.api.event.EventRecord;
import net.medievalrp.spyglass.api.util.Duration;
import net.medievalrp.spyglass.plugin.storage.RecordStore;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class AsyncRecorder implements Recorder {

    private final LinkedBlockingDeque<EventRecord> queue;
    private final RecordStore store;
    private final Logger logger;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final CountDownLatch stopped = new CountDownLatch(1);
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong drained = new AtomicLong();

    public AsyncRecorder(int capacity, RecordStore store, Logger logger) {
        this.queue = new LinkedBlockingDeque<>(capacity);
        this.store = store;
        this.logger = logger;
        Thread.ofVirtual()
                .name("sg-drain")
                .start(this::drainLoop);
    }

    @Override
    public void record(EventRecord record) {
        if (!queue.offer(record)) {
            long count = dropped.incrementAndGet();
            if (count == 1 || count % 100 == 0) {
                logger.warning("Spyglass recorder queue is full; dropped " + count + " records so far.");
            }
        }
    }

    @Override
    public ShutdownReport shutdown(Duration timeout) {
        running.set(false);
        try {
            stopped.await(timeout.seconds(), TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        flushRemaining();
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
                List<EventRecord> batch = new ArrayList<>();
                batch.add(first);
                queue.drainTo(batch, 511);

                // Persist with retry + exponential backoff. A transient store
                // failure (Mongo hiccup, replica-set election, network blip)
                // must not kill the drain thread and silently drop every
                // subsequent record. We retry the same batch until either it
                // succeeds or shutdown is requested, then loop back to polling.
                while (true) {
                    try {
                        store.save(batch);
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
                            for (int i = batch.size() - 1; i >= 0; i--) {
                                if (!queue.offerFirst(batch.get(i))) {
                                    dropped.incrementAndGet();
                                }
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

    private void flushRemaining() {
        List<EventRecord> batch = new ArrayList<>();
        queue.drainTo(batch);
        if (batch.isEmpty()) {
            return;
        }
        try {
            store.save(batch);
            drained.addAndGet(batch.size());
        } catch (RuntimeException ex) {
            logger.severe("Recorder shutdown flush failed; " + batch.size()
                    + " records lost: " + ex.getMessage());
            dropped.addAndGet(batch.size());
        }
    }

    public record ShutdownReport(long drained, long dropped, long remaining) {
    }
}
