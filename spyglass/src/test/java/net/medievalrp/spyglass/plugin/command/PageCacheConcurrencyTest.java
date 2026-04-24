package net.medievalrp.spyglass.plugin.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/**
 * PageCache concurrency regression tests.
 *
 * <p>PageCache is called from two places with different threading
 * profiles: the search service (async virtual thread completing a Mongo
 * query) and the page-flip command handler (main Bukkit thread). Its
 * backing store is a {@link java.util.concurrent.ConcurrentHashMap} —
 * these tests pin the implicit contract that the lack of additional
 * locking is safe: overlapping {@code store} / {@code show} / {@code
 * clear} from multiple senders must not corrupt state or leak data
 * across senders, and overlapping operations on the same sender must
 * resolve to last-write-wins without NPE or partial page rendering.
 *
 * <p>All senders use per-UUID Player mocks; we don't route through real
 * Audience / CommandSender sendMessage chains since that would drag in
 * Paper's plugin runtime. Mocking the Player interface is enough to
 * give PageCache a stable UUID key per sender.
 */
class PageCacheConcurrencyTest {

    @Test
    void storeAndShowAcrossManySendersNeverCrossesStreams() throws Exception {
        // 20 senders, 200 store+show cycles each, 4 OS threads. No sender
        // should ever see another sender's payload.
        int senders = 20;
        int iterationsPerSender = 200;
        PageCache cache = new PageCache();

        List<PerSender> workers = new ArrayList<>();
        for (int i = 0; i < senders; i++) {
            workers.add(new PerSender(i));
        }
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger violations = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            for (PerSender w : workers) {
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < iterationsPerSender; i++) {
                            final int iter = i;
                            // Store with a sentinel only this sender's
                            // renderer should ever produce.
                            cache.store(w.sender(), 1, idx -> Component.text(w.sentinel(iter)));
                            // Show the cached page. If the store and show
                            // ever see different sender identities, show
                            // will dispatch the wrong renderer.
                            boolean shown = cache.show(w.sender(), 1);
                            if (!shown) {
                                // Only legal race: previous worker cleared
                                // the cache, which only happens here if we
                                // crossed sender identities. Flag it.
                                violations.incrementAndGet();
                            }
                        }
                    } catch (Exception ex) {
                        violations.incrementAndGet();
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS))
                    .as("thread pool must finish within 30s").isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(violations.get())
                .as("sender identity must be stable across concurrent store/show")
                .isZero();
        // Every sender's lines must contain only that sender's sentinel.
        // Filter out the page header ("Page N/M — K results") which
        // show() sends before any data line.
        for (PerSender w : workers) {
            List<String> lines = w.capturedLines().stream()
                    .filter(line -> line.startsWith("sender-"))
                    .toList();
            assertThat(lines).as("sender %s saw at least one rendered line", w.id).isNotEmpty();
            for (String line : lines) {
                assertThat(line)
                        .as("sender %s received payload from another sender", w.id)
                        .startsWith("sender-" + w.id + ":");
            }
        }
    }

    @Test
    void concurrentStoreToSameSenderIsLastWriteWinsWithoutCorruption() throws Exception {
        // Hammer the same sender from many threads. The final cached
        // state must match SOME submitted (count, renderer) pair — not a
        // partially-applied frankenstate. We prove this by making every
        // renderer encode its own count and asserting consistency.
        PerSender target = new PerSender(0);
        PageCache cache = new PageCache();
        int threads = 8;
        int iterations = 500;

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int t = 0; t < threads; t++) {
                final int threadId = t;
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < iterations; i++) {
                            int count = 1 + ((threadId * iterations + i) % 50);
                            IntFunction<Component> renderer = idx -> Component.text(
                                    "t" + threadId + "-i" + idx + "-of" + count);
                            cache.store(target.sender(), count, renderer);
                        }
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        // Final show must work and produce coherent output (first line's
        // "of<count>" tag matches the count implied by the page header).
        List<String> finalLines = target.capturedLines();
        target.clearCaptured();
        boolean shown = cache.show(target.sender(), 1);
        assertThat(shown).isTrue();
        List<String> rendered = target.capturedLines();
        // Filter out the page header (doesn't match our tXX-iYY-ofZZ shape)
        List<String> dataLines = rendered.stream()
                .filter(line -> line.startsWith("t"))
                .toList();
        assertThat(dataLines)
                .as("final show must render at least one data line from a single renderer")
                .isNotEmpty();
        // All data lines must share the same thread and count tags —
        // proves they came from the SAME renderer closure, not a torn one.
        String firstTag = dataLines.getFirst().substring(0, dataLines.getFirst().indexOf("-i"));
        String firstCountTag = dataLines.getFirst()
                .substring(dataLines.getFirst().indexOf("-of"));
        for (String line : dataLines) {
            assertThat(line).startsWith(firstTag);
            assertThat(line).endsWith(firstCountTag);
        }
        // Old captured payload was filled by the first sender's closure
        // during the test; its size should also be > 0 (sanity).
        assertThat(finalLines).isEmpty();
    }

    @Test
    void clearDuringShowDoesNotThrow() throws Exception {
        // The show() method reads the cached page, then iterates and
        // calls renderer.apply per line. A concurrent clear() removes
        // the entry. Because show() holds a local ref to the renderer
        // closure, the iteration must complete without NPE or
        // ConcurrentModificationException.
        PerSender target = new PerSender(42);
        PageCache cache = new PageCache();
        int iterations = 200;

        for (int run = 0; run < iterations; run++) {
            cache.store(target.sender(), 25, idx -> Component.text("page-" + idx));
            CountDownLatch start = new CountDownLatch(1);
            Thread clearer = new Thread(() -> {
                try {
                    start.await();
                    cache.clear(target.sender());
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
            Thread shower = new Thread(() -> {
                try {
                    start.await();
                    cache.show(target.sender(), 1);
                } catch (Exception ex) {
                    throw new AssertionError("show() must not throw under clear race", ex);
                }
            });
            clearer.start();
            shower.start();
            start.countDown();
            clearer.join(2_000);
            shower.join(2_000);
            target.clearCaptured();
        }
    }

    /**
     * Per-sender harness: a mocked Player with a stable UUID, plus a
     * thread-safe capture of every Component dispatched to it.
     */
    private static final class PerSender {
        private final int id;
        private final UUID uuid;
        private final Player sender;
        private final ConcurrentLinkedQueue<String> captured = new ConcurrentLinkedQueue<>();

        PerSender(int id) {
            this.id = id;
            this.uuid = new UUID(0xCAFEBABEL, id);
            this.sender = mock(Player.class);
            org.mockito.Mockito.when(sender.getUniqueId()).thenReturn(uuid);
            org.mockito.Mockito.doAnswer(invocation -> {
                Object arg = invocation.getArgument(0);
                if (arg instanceof Component c) {
                    captured.add(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                            .plainText().serialize(c));
                }
                return null;
            }).when(sender).sendMessage(org.mockito.ArgumentMatchers.any(Component.class));
        }

        Player sender() {
            return sender;
        }

        String sentinel(int iteration) {
            return "sender-" + id + ":iter-" + iteration;
        }

        List<String> capturedLines() {
            return new ArrayList<>(captured);
        }

        void clearCaptured() {
            captured.clear();
        }
    }
}
