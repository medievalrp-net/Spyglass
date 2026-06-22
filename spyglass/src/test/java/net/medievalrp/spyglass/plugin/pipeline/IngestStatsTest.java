package net.medievalrp.spyglass.plugin.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * #168: the opt-in analytics counter. Pins that {@link IngestStats#onRecord}
 * tallies per type, {@link IngestStats#capture} reflects the counters plus the
 * live gauges, and {@link IngestStats#describe} computes correct per-second
 * rates over a window (busiest type first, allocation "n/a" when unsupported).
 */
class IngestStatsTest {

    @Test
    void onRecordTalliesPerTypeAndTotal() {
        IngestStats stats = new IngestStats(() -> 7, () -> 100L, () -> 0L, () -> 12_345L);
        stats.onRecord("break");
        stats.onRecord("break");
        stats.onRecord("break");
        stats.onRecord("place");
        stats.onRecord("place");

        IngestStats.Snapshot snap = stats.capture(0L);
        assertThat(snap.total()).isEqualTo(5L);
        assertThat(snap.counts()).containsEntry("break", 3L).containsEntry("place", 2L);
        // Gauges are read through the suppliers at capture time.
        assertThat(snap.queueDepth()).isEqualTo(7);
        assertThat(snap.drained()).isEqualTo(100L);
        assertThat(snap.dropped()).isEqualTo(0L);
        assertThat(snap.allocatedBytes()).isEqualTo(12_345L);
    }

    @Test
    void nullOrEmptyEventTypeFoldsToQuestionMark() {
        IngestStats stats = new IngestStats(() -> 0, () -> 0L, () -> 0L, () -> -1L);
        stats.onRecord(null);
        stats.onRecord("");
        assertThat(stats.capture(0L).counts()).containsEntry("?", 2L);
    }

    @Test
    void describeComputesPerSecondRatesBusiestFirst() {
        IngestStats.Snapshot prev = new IngestStats.Snapshot(
                0L, 0L, Map.of(), 0, 0L, 0L, 0L);
        Map<String, Long> counts = new TreeMap<>();
        counts.put("break", 120L);
        counts.put("place", 80L);
        long twoSeconds = 2_000_000_000L;
        long allocAfter = (long) (2.0 * 28 * 1_048_576); // 28 MB/s over 2s
        IngestStats.Snapshot now = new IngestStats.Snapshot(
                twoSeconds, 200L, counts, 5, 150L, 0L, allocAfter);

        List<String> lines = IngestStats.describe(prev, now);

        assertThat(lines.get(0))
                .contains("ingest 100.0 ev/s")
                .contains("queue=5")
                .contains("persisted 75.0/s (total 150)")
                .contains("dropped 0")
                .contains("28.0 MB/s");
        // Busiest type first: break (60/s, 120 in window) before place (40/s, 80).
        assertThat(lines.get(1)).contains("break").contains("60.0").contains("(120)");
        assertThat(lines.get(2)).contains("place").contains("40.0").contains("(80)");
        assertThat(lines).hasSize(3);
    }

    @Test
    void describeRendersAllocationNotAvailableWhenNegative() {
        IngestStats.Snapshot prev = new IngestStats.Snapshot(0L, 0L, Map.of(), 0, 0L, 0L, -1L);
        IngestStats.Snapshot now = new IngestStats.Snapshot(
                1_000_000_000L, 10L, Map.of("chat", 10L), 0, 10L, 0L, -1L);
        List<String> lines = IngestStats.describe(prev, now);
        assertThat(lines.get(0)).contains("alloc n/a");
    }

    @Test
    void describeReportsAnEmptyWindowExplicitly() {
        IngestStats.Snapshot a = new IngestStats.Snapshot(0L, 0L, Map.of(), 0, 0L, 0L, 0L);
        IngestStats.Snapshot b = new IngestStats.Snapshot(1_000_000_000L, 0L, Map.of(), 0, 0L, 0L, 0L);
        List<String> lines = IngestStats.describe(a, b);
        assertThat(lines).hasSize(2);
        assertThat(lines.get(1)).contains("no events");
    }
}
