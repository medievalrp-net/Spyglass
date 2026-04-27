package net.medievalrp.spyglass.plugin.storage;

import java.util.List;
import net.medievalrp.spyglass.api.event.EventRecord;
import net.medievalrp.spyglass.api.query.QueryRequest;
import net.medievalrp.spyglass.api.query.QueryResult;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public interface RecordStore extends AutoCloseable {

    void save(List<EventRecord> records);

    /**
     * Full-fat query: hydrates every persisted field of every record.
     * Use for rollback / restore / inspect — any path that has to reason
     * about the pre/post block state or the exact item payload.
     */
    QueryResult query(QueryRequest request);

    /**
     * Display-only query: hydrates just the fields the search renderer
     * needs (event, origin, source, location, target, scalar extras like
     * amount/damage/recipients/commandLine/address).
     *
     * <p>Specifically drops the deeply-nested block and item snapshots
     * (originalBlock, newBlock, beforeItem, afterItem, item). Those
     * fields can run to hundreds of bytes each and are the dominant
     * per-record allocation when deserializing a 1 000-result page.
     * Filtering still runs server-side against the full document, so
     * predicates that reference projected-away fields (iname, ilore,
     * ench, etc.) continue to work.
     *
     * <p>Default implementation falls back to {@link #query} so
     * non-Mongo stores (in-memory test doubles) don't have to split
     * their paths.
     */
    default QueryResult querySummary(QueryRequest request) {
        return query(request);
    }

    @Override
    void close();
}
