package net.medievalrp.spyglass.plugin.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

/**
 * #168: the periodic reporter captures a snapshot, describes the window since
 * its previous run, and logs it. Driven with a deterministic clock so the rate
 * math is exact.
 */
class IngestStatsReporterTest {

    @Test
    void runLogsAnIngestReportForTheWindow() {
        IngestStats stats = new IngestStats(() -> 0, () -> 0L, () -> 0L, () -> -1L);
        long[] clock = {1_000_000_000L};

        List<String> logged = new ArrayList<>();
        Logger logger = Logger.getLogger("ingest-reporter-test");
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.INFO.intValue()) {
                    logged.add(record.getMessage());
                }
            }
            @Override public void flush() { }
            @Override public void close() { }
        });

        // Baseline captured at t=1s.
        IngestStatsReporter reporter = new IngestStatsReporter(stats, logger, () -> clock[0]);

        stats.onRecord("break");
        stats.onRecord("break");
        stats.onRecord("place");
        clock[0] = 3_000_000_000L; // +2s window

        reporter.run();

        assertThat(logged).hasSize(1);
        assertThat(logged.get(0))
                .contains("Spyglass analytics:")
                .contains("ingest")
                .contains("break");
    }
}
