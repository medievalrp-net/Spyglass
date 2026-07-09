package net.medievalrp.spyglass.plugin.importer.sink;

import java.util.HashMap;
import java.util.Map;
import net.medievalrp.spyglass.api.event.EventRecord;

/**
 * No-op sink that just counts records by event type. Used by
 * {@code --dry-run} to validate the source + mapper without writing
 * to ClickHouse.
 */
public final class CountingSink implements RecordSink {

    private final Map<String, Long> counts = new HashMap<>();
    private long total;

    @Override
    public void accept(EventRecord record) {
        counts.merge(record.event(), 1L, Long::sum);
        total++;
    }

    @Override public void flush() {}

    @Override public long written() { return total; }

    public Map<String, Long> perEventCounts() {
        return Map.copyOf(counts);
    }

    @Override public void close() {}
}
