package net.medievalrp.spyglass.plugin.pipeline;

import java.util.List;
import java.util.function.LongSupplier;
import java.util.logging.Logger;
import org.jetbrains.annotations.ApiStatus;

/**
 * Periodic analytics reporter (#168). Scheduled by the plugin as a repeating
 * async task; each run captures a fresh {@link IngestStats.Snapshot} and logs
 * the per-second ingest rates over the window since its previous run, so the
 * console shows a live load trail (events/sec by type, queue depth, drain
 * throughput, background allocation rate).
 *
 * <p>Runs off the main thread: {@link IngestStats#capture} only reads
 * concurrent counters and cheap gauges.
 */
@ApiStatus.Internal
public final class IngestStatsReporter implements Runnable {

    private final IngestStats stats;
    private final Logger logger;
    private final LongSupplier nanoTime;
    // Written at the end of each run(), read at the start of the next. The async
    // scheduler may run consecutive ticks on different pool threads, so volatile
    // makes the cross-run handoff of this reference explicit (one ref write/run).
    private volatile IngestStats.Snapshot last;

    public IngestStatsReporter(IngestStats stats, Logger logger) {
        this(stats, logger, System::nanoTime);
    }

    /** Visible for tests: inject a deterministic clock. */
    IngestStatsReporter(IngestStats stats, Logger logger, LongSupplier nanoTime) {
        this.stats = stats;
        this.logger = logger;
        this.nanoTime = nanoTime;
        // Baseline at construction (analytics-enable time) so the first run
        // reports rates over [enable, +interval], not since the JVM epoch.
        this.last = stats.capture(nanoTime.getAsLong());
    }

    @Override
    public void run() {
        IngestStats.Snapshot now = stats.capture(nanoTime.getAsLong());
        List<String> lines = IngestStats.describe(last, now);
        StringBuilder out = new StringBuilder("Spyglass analytics: ").append(lines.get(0));
        for (int i = 1; i < lines.size(); i++) {
            out.append(System.lineSeparator()).append(lines.get(i));
        }
        logger.info(out.toString());
        last = now;
    }
}
