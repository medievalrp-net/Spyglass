package net.medievalrp.spyglass.plugin.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import net.medievalrp.spyglass.api.util.Duration;
import org.junit.jupiter.api.Test;

/**
 * Contract for the off-thread serialization stage (#98). The flush
 * coordination ({@link DeferredSerializer#awaitQuiescence}) is what lets a
 * rollback drain in-flight rollbackable container serialization before it
 * reads, so it gets the most attention here.
 */
class DeferredSerializerTest {

    private final Logger logger = mock(Logger.class);

    @Test
    void runsSubmittedTaskAndReachesQuiescence() {
        DeferredSerializer serializer = new DeferredSerializer(logger);
        AtomicBoolean ran = new AtomicBoolean(false);

        serializer.execute(() -> ran.set(true));

        assertThat(serializer.awaitQuiescence(Duration.parse("5s"))).isTrue();
        assertThat(ran).isTrue();
        serializer.shutdown(Duration.parse("1s"));
    }

    @Test
    void awaitQuiescenceBlocksUntilInFlightTaskCompletes() throws InterruptedException {
        DeferredSerializer serializer = new DeferredSerializer(logger);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        serializer.execute(() -> {
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        // The task is parked, so quiescence can't be reached in a short window.
        assertThat(serializer.awaitQuiescence(Duration.parse("1s")))
                .as("must not report quiescent while a task is still in flight")
                .isFalse();

        release.countDown();
        assertThat(serializer.awaitQuiescence(Duration.parse("5s"))).isTrue();
        serializer.shutdown(Duration.parse("1s"));
    }

    @Test
    void guardsTaskExceptionsAndStillReachesQuiescence() {
        DeferredSerializer serializer = new DeferredSerializer(logger);

        serializer.execute(() -> {
            throw new RuntimeException("poison item");
        });

        // A throwing task must not leak the in-flight count (else flush hangs)
        // and must be logged, not escape to stderr.
        assertThat(serializer.awaitQuiescence(Duration.parse("5s"))).isTrue();
        verify(logger, timeout(5_000)).warning(contains("poison item"));
        serializer.shutdown(Duration.parse("1s"));
    }

    @Test
    void poolsThreadsAndStaysCorrectUnderManySubmissions() {
        // The block break/place listeners (#154) submit a task per event. This
        // pins the two things the pooled rework changed: (1) the lock-free
        // in-flight counter settles to zero after a flood of concurrent
        // submissions (else a rollback flush would hang), and (2) tasks run on
        // a small reused pool, NOT one freshly-spawned thread per task (the
        // per-event spawn was the measured regression).
        DeferredSerializer serializer = new DeferredSerializer(logger);
        int n = 2000;
        java.util.Set<String> workerThreads = java.util.concurrent.ConcurrentHashMap.newKeySet();
        java.util.concurrent.atomic.AtomicInteger ran = new java.util.concurrent.atomic.AtomicInteger();

        for (int i = 0; i < n; i++) {
            serializer.execute(() -> {
                workerThreads.add(Thread.currentThread().getName());
                ran.incrementAndGet();
            });
        }

        assertThat(serializer.awaitQuiescence(Duration.parse("10s")))
                .as("lock-free counter must settle to zero after all submissions complete")
                .isTrue();
        assertThat(ran.get()).isEqualTo(n);
        assertThat(workerThreads)
                .allSatisfy(name -> assertThat(name).startsWith("spyglass-deferred-serializer-"));
        assertThat(workerThreads.size())
                .as("a fixed pool reuses a bounded number of threads, not one per task")
                .isLessThanOrEqualTo(Math.max(2, Runtime.getRuntime().availableProcessors()));
        serializer.shutdown(Duration.parse("2s"));
    }

    @Test
    void executeAfterShutdownRunsInlineSoRecordsAreNotDropped() {
        DeferredSerializer serializer = new DeferredSerializer(logger);
        serializer.shutdown(Duration.parse("1s"));
        AtomicBoolean ran = new AtomicBoolean(false);

        serializer.execute(() -> ran.set(true));

        assertThat(ran)
                .as("a rollbackable record must not be dropped once the executor is down")
                .isTrue();
        assertThat(serializer.awaitQuiescence(Duration.parse("1s"))).isTrue();
    }
}
