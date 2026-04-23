package net.medievalrp.spyglass.plugin.storage;

import java.util.List;
import net.medievalrp.spyglass.api.event.EventRecord;
import net.medievalrp.spyglass.api.query.QueryRequest;
import net.medievalrp.spyglass.api.query.QueryResult;

public interface RecordStore extends AutoCloseable {

    void save(List<EventRecord> records);

    QueryResult query(QueryRequest request);

    @Override
    void close();
}
