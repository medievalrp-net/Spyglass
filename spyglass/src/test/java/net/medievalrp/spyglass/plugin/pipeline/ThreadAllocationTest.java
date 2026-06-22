package net.medievalrp.spyglass.plugin.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * #168: the allocation sampler attributes Spyglass-owned background threads by a
 * case-insensitive {@code spyglass-} name prefix, so both the lowercase ingest
 * threads ({@code spyglass-drain}, {@code spyglass-deferred-serializer-*}) and
 * the capital-S rollback executors ({@code Spyglass-WorldWriter-*},
 * {@code Spyglass-RollbackRead}) are counted. Foreign threads are not.
 */
class ThreadAllocationTest {

    @Test
    void matchesSpyglassOwnedThreadsCaseInsensitively() {
        assertThat(ThreadAllocation.isSpyglassThread("spyglass-drain")).isTrue();
        assertThat(ThreadAllocation.isSpyglassThread("spyglass-deferred-serializer-3")).isTrue();
        assertThat(ThreadAllocation.isSpyglassThread("Spyglass-WorldWriter-1")).isTrue();
        assertThat(ThreadAllocation.isSpyglassThread("Spyglass-RollbackRead")).isTrue();
    }

    @Test
    void doesNotMatchForeignThreads() {
        assertThat(ThreadAllocation.isSpyglassThread("Server thread")).isFalse();
        assertThat(ThreadAllocation.isSpyglassThread("ForkJoinPool-1-worker-1")).isFalse();
        assertThat(ThreadAllocation.isSpyglassThread("Netty Server IO #2")).isFalse();
        assertThat(ThreadAllocation.isSpyglassThread(null)).isFalse();
        assertThat(ThreadAllocation.isSpyglassThread("")).isFalse();
    }

    @Test
    void spyglassAllocatedBytesReturnsMinusOneOrNonNegative() {
        // Either the JVM doesn't support per-thread allocation (-1) or it returns
        // a non-negative sum. Never throws, never a stray negative.
        long bytes = ThreadAllocation.spyglassAllocatedBytes();
        assertThat(bytes).isGreaterThanOrEqualTo(-1L);
    }
}
