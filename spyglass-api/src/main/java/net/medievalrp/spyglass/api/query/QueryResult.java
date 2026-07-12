package net.medievalrp.spyglass.api.query;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.medievalrp.spyglass.api.event.EventRecord;

/**
 * A query's results.
 *
 * <p>{@code rolledBackIds} names the ids among {@link #records()} (and
 * aggregation samples) that a rollback operation has since reverted, so
 * a display can mark them as undone. It is derived from rollback-op
 * coverage, not a stored flag, so it shares the same "the op's query
 * covered this block" approximation the synthesized rolled-* receipts
 * use. Empty unless the store was wrapped to compute it.
 */
public record QueryResult(List<EventRecord> records,
                          List<RecordAggregation> aggregations,
                          Set<UUID> rolledBackIds) {

    public QueryResult {
        records = List.copyOf(records);
        aggregations = List.copyOf(aggregations);
        rolledBackIds = rolledBackIds == null ? Set.of() : Set.copyOf(rolledBackIds);
    }

    /**
     * Result with no rolled-back annotations. Every backend's own query
     * path builds results this way; only the synthesis decorator
     * populates {@link #rolledBackIds()}.
     */
    public QueryResult(List<EventRecord> records, List<RecordAggregation> aggregations) {
        this(records, aggregations, Set.of());
    }

    public record RecordAggregation(EventRecord sample, long count) {
    }
}
