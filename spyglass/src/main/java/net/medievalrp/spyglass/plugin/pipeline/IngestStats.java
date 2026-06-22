package net.medievalrp.spyglass.plugin.pipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import org.jetbrains.annotations.ApiStatus;

/**
 * Opt-in ingest counters for the analytics mode (#168). When wired into the
 * recorder via {@link AsyncRecorder#setIngestStats}, every recorded event
 * increments a per-type counter; a {@link Snapshot} captures the cumulative
 * counts plus live pipeline gauges, and two snapshots taken apart in time give
 * per-second rates ({@link #describe}).
 *
 * <p>Cheap on the hot path: {@link #onRecord} is one concurrent-map lookup plus
 * a {@link LongAdder} increment, with no allocation after a type is first seen.
 * Counters are concurrent because records arrive from the main thread, the
 * deferred-serializer pool, and WorldEdit/FAWE worker threads. The gauges are
 * read through suppliers so this stays decoupled from the recorder and
 * unit-testable headless.
 */
@ApiStatus.Internal
public final class IngestStats {

    private final ConcurrentHashMap<String, LongAdder> perType = new ConcurrentHashMap<>();
    private final LongAdder total = new LongAdder();
    private final IntSupplier queueDepth;
    private final LongSupplier drained;
    private final LongSupplier dropped;
    private final LongSupplier allocatedBytes;

    /**
     * @param queueDepth     live recorder queue depth.
     * @param drained        cumulative records persisted to the store.
     * @param dropped        cumulative records lost (shutdown flush exhaustion).
     * @param allocatedBytes cumulative bytes allocated by Spyglass background
     *                       threads, or a constant negative value when the JVM
     *                       can't report it (rendered as "n/a").
     */
    public IngestStats(IntSupplier queueDepth, LongSupplier drained,
                       LongSupplier dropped, LongSupplier allocatedBytes) {
        this.queueDepth = queueDepth;
        this.drained = drained;
        this.dropped = dropped;
        this.allocatedBytes = allocatedBytes;
    }

    /**
     * Production wiring: the allocation gauge is the built-in best-effort
     * {@link ThreadAllocation} sampler (Spyglass background threads' churn, or
     * n/a when the JVM can't report it). The four-arg constructor stays for
     * tests that inject a deterministic allocation supplier.
     */
    public IngestStats(IntSupplier queueDepth, LongSupplier drained, LongSupplier dropped) {
        this(queueDepth, drained, dropped, ThreadAllocation::spyglassAllocatedBytes);
    }

    /** Hot path: count one recorded event by its type name (e.g. "break"). */
    public void onRecord(String eventType) {
        String key = eventType == null || eventType.isEmpty() ? "?" : eventType;
        perType.computeIfAbsent(key, k -> new LongAdder()).increment();
        total.increment();
    }

    /** Immutable capture of the cumulative counters plus live gauges. */
    public Snapshot capture(long nowNanos) {
        Map<String, Long> counts = new TreeMap<>();
        perType.forEach((k, v) -> counts.put(k, v.sum()));
        return new Snapshot(nowNanos, total.sum(), counts,
                queueDepth.getAsInt(), drained.getAsLong(),
                dropped.getAsLong(), allocatedBytes.getAsLong());
    }

    /** Cumulative counters and gauges at one instant. */
    public record Snapshot(long nanoTime, long total, Map<String, Long> counts,
                           int queueDepth, long drained, long dropped, long allocatedBytes) {
    }

    /**
     * Human-readable per-second report for the window between {@code prev} (the
     * older snapshot) and {@code now}. Returns a summary line followed by one
     * line per active event type, busiest first. Rates are over the window;
     * gauges (queue depth, totals) are the latest values. A negative
     * {@code allocatedBytes} renders the allocation figure as "n/a".
     */
    public static List<String> describe(Snapshot prev, Snapshot now) {
        // Floor the window at 1s: a /spyglass stats issued microseconds after
        // analytics is enabled would otherwise divide a few events by a tiny
        // elapsed and print an absurd rate. The periodic reporter's windows are
        // always >= the configured interval (>= 5s), so this never affects it.
        double secs = Math.max(1.0, (now.nanoTime() - prev.nanoTime()) / 1_000_000_000.0);
        double totalRate = (now.total() - prev.total()) / secs;
        double drainRate = (now.drained() - prev.drained()) / secs;
        String alloc = now.allocatedBytes() < 0
                ? "n/a"
                : String.format(Locale.ROOT, "%.1f MB/s",
                        Math.max(0L, now.allocatedBytes() - prev.allocatedBytes()) / secs / 1_048_576.0);

        List<String> lines = new ArrayList<>();
        // Same %.1f precision on the headline as the per-type lines, so a sub-1/s
        // rate never rounds to a "0" headline above non-zero detail.
        lines.add(String.format(Locale.ROOT,
                "ingest %.1f ev/s over %.0fs | queue=%d | persisted %.1f/s (total %d) | dropped %d | spyglass-threads alloc %s",
                totalRate, secs, now.queueDepth(), drainRate, now.drained(), now.dropped(), alloc));

        List<Map.Entry<String, Long>> byType = new ArrayList<>(now.counts().entrySet());
        byType.sort((a, b) -> Long.compare(delta(b, prev), delta(a, prev)));
        for (Map.Entry<String, Long> entry : byType) {
            long count = delta(entry, prev);
            if (count <= 0) {
                continue;
            }
            // Show the rate AND the raw count for the window, so a low-traffic
            // type that rounds to "0.0 /s" over a long window is still visibly
            // distinct from a truly idle one.
            lines.add(String.format(Locale.ROOT, "  %-24s %8.1f /s (%d)",
                    entry.getKey(), count / secs, count));
        }
        if (lines.size() == 1) {
            lines.add("  (no events recorded in this window)");
        }
        return lines;
    }

    private static long delta(Map.Entry<String, Long> entry, Snapshot prev) {
        return entry.getValue() - prev.counts().getOrDefault(entry.getKey(), 0L);
    }
}
