package net.medievalrp.spyglass.importer.bench;

import java.util.Arrays;

/**
 * Tiny stats helper. Records nanosecond samples and emits
 * median/p95/p99 plus min/max. Cheap to construct, no allocation
 * during {@link #record}.
 */
public final class Timed {

    private long[] samples;
    private int n;

    public Timed(int capacity) {
        this.samples = new long[capacity];
    }

    public void record(long nanos) {
        if (n >= samples.length) {
            samples = Arrays.copyOf(samples, samples.length * 2);
        }
        samples[n++] = nanos;
    }

    public int count() { return n; }

    /** Median, in milliseconds. */
    public double medianMs() { return percentileMs(50); }
    public double p95Ms() { return percentileMs(95); }
    public double p99Ms() { return percentileMs(99); }
    public double minMs() { return min() / 1_000_000.0; }
    public double maxMs() { return max() / 1_000_000.0; }
    public double meanMs() {
        if (n == 0) return 0;
        long total = 0;
        for (int i = 0; i < n; i++) total += samples[i];
        return (total / (double) n) / 1_000_000.0;
    }

    private double percentileMs(int p) {
        if (n == 0) return 0;
        long[] sorted = Arrays.copyOf(samples, n);
        Arrays.sort(sorted);
        // Nearest-rank percentile.
        int idx = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        idx = Math.max(0, Math.min(sorted.length - 1, idx));
        return sorted[idx] / 1_000_000.0;
    }

    private long min() {
        long m = Long.MAX_VALUE;
        for (int i = 0; i < n; i++) if (samples[i] < m) m = samples[i];
        return m == Long.MAX_VALUE ? 0 : m;
    }

    private long max() {
        long m = Long.MIN_VALUE;
        for (int i = 0; i < n; i++) if (samples[i] > m) m = samples[i];
        return m == Long.MIN_VALUE ? 0 : m;
    }
}
