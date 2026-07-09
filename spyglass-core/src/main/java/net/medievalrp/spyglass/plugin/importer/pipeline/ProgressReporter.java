package net.medievalrp.spyglass.plugin.importer.pipeline;

import java.io.PrintStream;
import java.util.Locale;

/**
 * Logs progress every {@code interval} rows, and prints a header line
 * each time the pipeline switches to a new table.
 */
public final class ProgressReporter {

    private final PrintStream out;
    private final long interval;
    private final long startNanos;
    private long lastReportRows;
    private long lastReportNanos;

    public ProgressReporter(PrintStream out, long interval) {
        this.out = out;
        this.interval = interval;
        this.startNanos = System.nanoTime();
        this.lastReportNanos = startNanos;
    }

    public void beginTable(String label) {
        out.println("Streaming " + label + "...");
    }

    public void endTable(String label, long rowsInTable) {
        out.printf(Locale.ROOT, "  %s done - %,d rows%n", label, rowsInTable);
    }

    public void onRow(long readSoFar) {
        if (readSoFar % interval != 0) {
            return;
        }
        long now = System.nanoTime();
        long sinceLastNanos = now - lastReportNanos;
        long sinceLastRows = readSoFar - lastReportRows;
        double instantRate = sinceLastRows * 1_000_000_000.0 / Math.max(1L, sinceLastNanos);
        double overallRate = readSoFar * 1_000_000_000.0 / Math.max(1L, now - startNanos);
        out.printf(Locale.ROOT,
                "  %,d rows  (instant %.0f r/s, overall %.0f r/s)%n",
                readSoFar, instantRate, overallRate);
        lastReportRows = readSoFar;
        lastReportNanos = now;
    }

    public long elapsedMillis() {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
