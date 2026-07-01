package net.medievalrp.spyglass.importer.sink;

import java.util.ArrayList;
import java.util.List;
import net.medievalrp.spyglass.api.event.EventRecord;
import net.medievalrp.spyglass.plugin.importer.sink.RecordSink;
import net.medievalrp.spyglass.plugin.storage.ClickHouseRecordStore;

/**
 * Buffers {@link EventRecord} instances and ships them to ClickHouse in
 * batches via {@link ClickHouseRecordStore#save}. The store handles the
 * RowBinary encoding, async-insert acknowledgement, and ZSTD compression
 * — the sink just controls batch size and flush cadence.
 *
 * <p>Caller must call {@link #flush()} before {@link #close()} to drain
 * the trailing partial batch.
 */
public final class ClickHouseSink implements RecordSink {

    private final ClickHouseRecordStore store;
    private final int batchSize;
    private final List<EventRecord> buffer;
    private long written;

    public ClickHouseSink(ClickHouseRecordStore store, int batchSize) {
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
        store.close();
    }
}
