package net.medievalrp.spyglass.plugin.storage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.medievalrp.spyglass.api.event.EventRecord;
import net.medievalrp.spyglass.api.query.QueryRequest;
import net.medievalrp.spyglass.api.query.QueryResult;
import net.medievalrp.spyglass.api.query.Sort;
import org.jetbrains.annotations.ApiStatus;

/**
 * {@link RecordStore} decorator that merges synthesized rolled-*
 * entries (see {@link RolledSynthesis}) into read results. Wrap the
 * concrete store once at wiring time and every consumer — plugin
 * search, the public API, the Velocity read path — inherits the
 * behavior; writes and the rollback's streaming page reads delegate
 * untouched.
 */
@ApiStatus.Internal
public final class SynthesizingRecordStore implements RecordStore {

    private final RecordStore delegate;
    private final RolledSynthesis synthesis;
    private final boolean enabled;

    public SynthesizingRecordStore(RecordStore delegate, boolean enabled) {
        this.delegate = delegate;
        this.synthesis = new RolledSynthesis(delegate);
        this.enabled = enabled;
    }

    @Override
    public void save(List<EventRecord> records) {
        delegate.save(records);
    }

    @Override
    public QueryResult query(QueryRequest request) {
        return merged(delegate.query(request), request);
    }

    @Override
    public QueryResult querySummary(QueryRequest request) {
        // Synthesized records are summary-light by construction (no
        // snapshot blobs), so the same merge serves both paths.
        return merged(delegate.querySummary(request), request);
    }

    @Override
    public QueryPage queryPage(QueryRequest request, QueryPage.Cursor cursor, int pageSize) {
        return delegate.queryPage(request, cursor, pageSize);
    }

    @Override
    public void close() {
        delegate.close();
    }

    private QueryResult merged(QueryResult base, QueryRequest request) {
        if (!enabled) {
            return base;
        }
        List<EventRecord> extra = synthesis.synthesize(request);
        if (extra.isEmpty()) {
            return base;
        }
        List<EventRecord> merged = new ArrayList<>(base.records());
        merged.addAll(extra);
        Comparator<EventRecord> byOccurred = Comparator.comparing(EventRecord::occurred)
                .thenComparing(record -> record.id().toString());
        merged.sort(request.sort() == Sort.OLDEST_FIRST ? byOccurred : byOccurred.reversed());
        if (merged.size() > request.limit()) {
            merged = merged.subList(0, request.limit());
        }
        return new QueryResult(merged, base.aggregations());
    }
}
