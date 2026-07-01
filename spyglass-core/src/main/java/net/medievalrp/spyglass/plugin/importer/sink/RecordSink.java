package net.medievalrp.spyglass.plugin.importer.sink;

import net.medievalrp.spyglass.api.event.EventRecord;

/**
 * Where mapped {@link EventRecord} instances go. The production
 * implementation is {@link ClickHouseSink}; {@link CountingSink} replaces
 * it for {@code --dry-run} so the importer can be exercised against a
 * source DB without provisioning ClickHouse.
 */
public interface RecordSink extends AutoCloseable {

    void accept(EventRecord record);

    void flush();

    long written();

    @Override
    void close();
}
