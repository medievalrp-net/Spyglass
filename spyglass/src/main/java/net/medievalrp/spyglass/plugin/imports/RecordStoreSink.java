package net.medievalrp.spyglass.plugin.imports;

import java.util.ArrayList;
import java.util.List;
import net.medievalrp.spyglass.api.event.EventRecord;
import net.medievalrp.spyglass.plugin.importer.sink.RecordSink;
import net.medievalrp.spyglass.plugin.storage.RecordStore;

/**
 * Import sink that writes mapped {@link EventRecord}s through the running
 * plugin's configured {@link RecordStore}. Mirrors the CLI's ClickHouseSink
 * batching, but is backend-agnostic and — unlike the CLI sink — never closes
 * the store: the plugin owns the store's lifecycle for live ingest.
 */
public final class RecordStoreSink implements RecordSink {

    private final RecordStore store;
    private final int batchSize;
    private final List<EventRecord> buffer;
    private long written;

    public RecordStoreSink(RecordStore store, int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be >= 1, was " + batchSize);
        }
        this.store = store;
        this.batchSize = batchSize;
        this.buffer = new ArrayList<>(batchSize);
    }

    @Override
    public void accept(EventRecord record) {
        buffer.add(record);
        if (buffer.size() >= batchSize) {
            flush();
        }
    }

    @Override
    public void flush() {
        if (buffer.isEmpty()) {
            return;
        }
        store.save(buffer);
        written += buffer.size();
        buffer.clear();
    }

    @Override
    public long written() {
        return written;
    }

    @Override
    public void close() {
        flush();
        // Intentionally does NOT close the store.
    }
}
