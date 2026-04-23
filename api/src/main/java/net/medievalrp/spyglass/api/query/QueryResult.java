package net.medievalrp.spyglass.api.query;

import java.util.List;
import net.medievalrp.spyglass.api.event.EventRecord;

public record QueryResult(List<EventRecord> records, List<RecordAggregation> aggregations) {

    public QueryResult {
        records = List.copyOf(records);
        aggregations = List.copyOf(aggregations);
    }

    public record RecordAggregation(EventRecord sample, long count) {
    }
}
