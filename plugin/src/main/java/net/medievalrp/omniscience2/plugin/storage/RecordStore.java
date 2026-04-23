package net.medievalrp.omniscience2.plugin.storage;

import java.util.List;
import net.medievalrp.omniscience2.api.event.EventRecord;
import net.medievalrp.omniscience2.api.query.QueryRequest;
import net.medievalrp.omniscience2.api.query.QueryResult;

public interface RecordStore extends AutoCloseable {

    void save(List<EventRecord> records);

    QueryResult query(QueryRequest request);

    @Override
    void close();
}
