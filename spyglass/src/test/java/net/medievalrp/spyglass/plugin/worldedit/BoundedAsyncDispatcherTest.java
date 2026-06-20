package net.medievalrp.spyglass.plugin.worldedit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class BoundedAsyncDispatcherTest {

    @Test
    @Timeout(10)
    void runsInlineOnTheCallerWhenAllSlotsAreBusy() throws Exception {
        // The #121 backpressure: once the in-flight cap is reached, a dispatch
        // must run on the CALLING thread (so a huge WorldEdit op paces itself to
        // the build/spill rate instead of spawning unbounded builders → OOM).
        ExecutorService pool = Executors.newCachedThreadPool();
        try {
            BoundedAsyncDispatcher dispatcher = new BoundedAsyncDispatcher(pool, 1);
            CountDownLatch freeTheSlot = new CountDownLatch(1);
            CountDownLatch asyncStarted = new CountDownLatch(1);
            // Occupy the only slot with a task that blocks until released.
            dispatcher.dispatch(() -> {
                asyncStarted.countDown();
                try {
                    freeTheSlot.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            assertThat(asyncStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(dispatcher.availableSlots()).isZero();

            AtomicReference<Thread> ranOn = new AtomicReference<>();
            dispatcher.dispatch(() -> ranOn.set(Thread.currentThread()));
            assertThat(ranOn.get())
                    .as("a saturated dispatch must run inline on the calling thread")
                    .isSameAs(Thread.currentThread());

            freeTheSlot.countDown();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @Timeout(10)
    void releasesTheSlotAfterTheAsyncTaskFinishes() throws Exception {
        ExecutorService pool = Executors.newCachedThreadPool();
        try {
            BoundedAsyncDispatcher dispatcher = new BoundedAsyncDispatcher(pool, 2);
            CountDownLatch done = new CountDownLatch(1);
            dispatcher.dispatch(done::countDown);
            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
            long deadline = System.currentTimeMillis() + 2_000L;
            while (dispatcher.availableSlots() < 2 && System.currentTimeMillis() < deadline) {
                Thread.sleep(10L);
            }
            assertThat(dispatcher.availableSlots())
                    .as("the slot must be released once the async task completes")
                    .isEqualTo(2);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @Timeout(10)
    void runsInlineAndReleasesTheSlotWhenTheExecutorRejects() {
        // Executor shut down (server stopping): the task must still run (inline),
        // never be dropped, and the speculatively-acquired permit must be freed.
        ExecutorService pool = Executors.newSingleThreadExecutor();
        pool.shutdownNow();
        BoundedAsyncDispatcher dispatcher = new BoundedAsyncDispatcher(pool, 4);
        AtomicReference<Thread> ranOn = new AtomicReference<>();
        dispatcher.dispatch(() -> ranOn.set(Thread.currentThread()));
        assertThat(ranOn.get())
                .as("a rejected task must run inline, never drop")
                .isSameAs(Thread.currentThread());
        assertThat(dispatcher.availableSlots())
                .as("the permit must be released after a rejected submit")
                .isEqualTo(4);
    }
}
