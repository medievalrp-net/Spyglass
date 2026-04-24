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
        try {
            while (running.get() || !queue.isEmpty()) {
                EventRecord first = queue.poll(250, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                List<EventRecord> batch = new ArrayList<>();
                batch.add(first);
                queue.drainTo(batch, 511);
                store.save(batch);
                drained.addAndGet(batch.size());
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception exception) {
            logger.severe("Spyglass recorder drain failed: " + exception.getMessage());
            exception.printStackTrace();
        } finally {
            stopped.countDown();
        }
    }

    private void flushRemaining() {
        List<EventRecord> batch = new ArrayList<>();
        queue.drainTo(batch);
        if (!batch.isEmpty()) {
            store.save(batch);
            drained.addAndGet(batch.size());
        }
    }

    public record ShutdownReport(long drained, long dropped, long remaining) {
    }
}
