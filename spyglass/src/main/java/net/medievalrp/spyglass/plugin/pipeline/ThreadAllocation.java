package net.medievalrp.spyglass.plugin.pipeline;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.util.Locale;

/**
 * Best-effort sampler of cumulative bytes allocated by Spyglass's own
 * background threads, for the analytics allocation-rate line (#168). A
 * real-server spark profile flagged heavy async allocation on the storage write
 * path, so this lets operators watch Spyglass's attributable churn from inside
 * the plugin.
 *
 * <h2>Scope (read before trusting the number)</h2>
 *
 * It sums {@code getThreadAllocatedBytes} over live threads whose name starts
 * (case-insensitively) with {@code spyglass-}: the ingest drain thread
 * ({@code spyglass-drain}), the deferred-serializer pool
 * ({@code spyglass-deferred-serializer-*}), and the rollback executors
 * ({@code Spyglass-WorldWriter-*}, {@code Spyglass-RollbackRead}). It does
 * <b>not</b> attribute:
 * <ul>
 *   <li>the storage driver's own internal threads (the ClickHouse HTTP client
 *       pool, the Mongo driver pool) - so a backend that encodes/compresses on
 *       its own threads will under-report here; cross-check spark for those;</li>
 *   <li>WorldEdit/FAWE intake threads, which are owned by WorldEdit and cannot
 *       be attributed to Spyglass.</li>
 * </ul>
 *
 * <p>Returns {@code -1} when the JVM doesn't expose per-thread allocation
 * accounting (non-HotSpot, or a restricted context); callers render that as
 * "n/a". Read-only sampling. The periodic reporter calls it off the main thread;
 * {@code /spyglass stats} reaches it on the main thread, where it does one
 * bounded full-thread-table walk (acceptable for an operator-gated command).
 */
final class ThreadAllocation {

    private static final ThreadMXBean BEAN = resolve();
    private static final String PREFIX = "spyglass-";

    private ThreadAllocation() {
    }

    private static ThreadMXBean resolve() {
        try {
            java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
            if (bean instanceof ThreadMXBean sun && sun.isThreadAllocatedMemorySupported()) {
                sun.setThreadAllocatedMemoryEnabled(true);
                return sun;
            }
        } catch (RuntimeException | LinkageError unsupported) {
            // Non-HotSpot JVM or restricted context: allocation accounting off.
        }
        return null;
    }

    /** Whether a thread name belongs to a Spyglass-owned background thread. */
    static boolean isSpyglassThread(String name) {
        return name != null && name.toLowerCase(Locale.ROOT).startsWith(PREFIX);
    }

    /** Cumulative bytes allocated by live Spyglass-owned threads, or -1. */
    static long spyglassAllocatedBytes() {
        ThreadMXBean bean = BEAN;
        if (bean == null) {
            return -1L;
        }
        try {
            long sum = 0L;
            for (Thread thread : liveThreads()) {
                if (thread != null && isSpyglassThread(thread.getName())) {
                    long bytes = bean.getThreadAllocatedBytes(thread.threadId());
                    if (bytes > 0L) {
                        sum += bytes;
                    }
                }
            }
            return sum;
        } catch (RuntimeException sampleFailure) {
            return -1L;
        }
    }

    /**
     * Snapshot of the live threads. Re-enumerates into a larger array while the
     * result saturates the buffer, so a thread count that grows mid-walk never
     * silently truncates (the standard {@link ThreadGroup#enumerate} idiom).
     */
    private static Thread[] liveThreads() {
        ThreadGroup root = rootGroup();
        int size = root.activeCount() * 2 + 64;
        while (true) {
            Thread[] threads = new Thread[size];
            int count = root.enumerate(threads, true);
            if (count < threads.length) {
                Thread[] exact = new Thread[count];
                System.arraycopy(threads, 0, exact, 0, count);
                return exact;
            }
            size *= 2; // saturated: the table grew past our buffer, retry larger.
        }
    }

    private static ThreadGroup rootGroup() {
        ThreadGroup group = Thread.currentThread().getThreadGroup();
        while (group.getParent() != null) {
            group = group.getParent();
        }
        return group;
    }
}
