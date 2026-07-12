package net.medievalrp.spyglass.plugin.storage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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
        this(delegate, enabled, java.util.Set.of());
    }

    /**
     * @param containerMaterials material names whose block state is a
     *     container, from the caller's registry - lets synthesis honor
     *     an op's missing --containers flag for block writes too (#302)
     */
    public SynthesizingRecordStore(RecordStore delegate, boolean enabled,
                                   java.util.Set<String> containerMaterials) {
        this.delegate = delegate;
        this.synthesis = new RolledSynthesis(delegate, containerMaterials);
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
    public java.util.UUID resolvePlayerId(String playerName) {
        // MUST delegate explicitly: this method has an interface default
        // (returns null = "unknown"), so a decorator that doesn't forward it
        // silently disables the backend's imported-player name resolution -
        // exactly what happened live (the plugin always wraps every store
        // in this decorator).
        return delegate.resolvePlayerId(playerName);
    }

    @Override
    public QueryPage.Cursor streamRollback(QueryRequest request, QueryPage.Cursor cursor,
                                           int windowLimit, RecordSink sink) {
        // Rollback reads the real recorded events, never the synthesized
        // rolled-* receipts — you don't roll back a rollback's audit
        // trail. Delegate straight through so the concrete store's
        // wire-streaming reader is used (the interface default would route
        // back through queryPage and materialize a full page list).
        return delegate.streamRollback(request, cursor, windowLimit, sink);
    }

    @Override
    public QueryPage.Cursor streamRollbackEffects(QueryRequest request, QueryPage.Cursor cursor,
                                                  int windowLimit, boolean rollback,
                                                  RollbackEffectSink sink) {
        return delegate.streamRollbackEffects(request, cursor, windowLimit, rollback, sink);
    }

    @Override
    public void close() {
        delegate.close();
    }

    private QueryResult merged(QueryResult base, QueryRequest request) {
        if (!enabled) {
            return base;
        }
        // Mark the displayed originals a rollback has since reverted, so a
        // display can strike them through (#319). This runs over the records
        // already in hand, independent of whether any rolled-* receipt is
        // synthesized below - so p:-filtered lookups get it too, even though
        // no environment-sourced receipt could match such a query.
        Set<UUID> rolledBackIds = synthesis.rolledBackAmong(request, candidates(base));
        List<EventRecord> extra = synthesis.synthesize(request);
        if (extra.isEmpty()) {
            return rolledBackIds.isEmpty()
                    ? base
                    : new QueryResult(base.records(), base.aggregations(), rolledBackIds);
        }
        List<EventRecord> merged = new ArrayList<>(base.records());
        merged.addAll(extra);
        Comparator<EventRecord> byOccurred = Comparator.comparing(EventRecord::occurred)
                .thenComparing(record -> record.id().toString());
        merged.sort(request.sort() == Sort.OLDEST_FIRST ? byOccurred : byOccurred.reversed());
        if (merged.size() > request.limit()) {
            merged = merged.subList(0, request.limit());
        }
        // The default search and the wand render AGGREGATIONS when grouping
        // is on, and the renderer only falls back to records() when the
        // aggregation list is empty - so receipts merged into records()
        // alone were invisible in every grouped view and only surfaced on
        // queries that matched zero persisted rows (a:rolled-* searches).
        // Fold the synthesized receipts into the grouped side too.
        List<QueryResult.RecordAggregation> aggregations = base.aggregations();
        if (request.grouping()) {
            List<QueryResult.RecordAggregation> combined = new ArrayList<>(aggregations);
            combined.addAll(aggregate(extra));
            Comparator<QueryResult.RecordAggregation> aggByOccurred =
                    Comparator.comparing(aggregation -> aggregation.sample().occurred());
            combined.sort(request.sort() == Sort.OLDEST_FIRST
                    ? aggByOccurred : aggByOccurred.reversed());
            aggregations = combined;
        }
        return new QueryResult(merged, aggregations, rolledBackIds);
    }

    /**
     * Displayed originals a rollback could have reverted: the record rows
     * plus each aggregation's representative sample. Receipts are folded in
     * later and are never candidates - their env-sourced ids never match.
     */
    private static List<EventRecord> candidates(QueryResult base) {
        List<EventRecord> candidates = new ArrayList<>(base.records());
        for (QueryResult.RecordAggregation aggregation : base.aggregations()) {
            candidates.add(aggregation.sample());
        }
        return candidates;
    }

    /**
     * Group synthesized receipts the way the stores group persisted rows:
     * one line per (event, target, operator), counted - "ROLLBACK broke
     * STONE x64" instead of sixty-four rows.
     */
    private static List<QueryResult.RecordAggregation> aggregate(List<EventRecord> extra) {
        java.util.LinkedHashMap<String, QueryResult.RecordAggregation> groups =
                new java.util.LinkedHashMap<>();
        for (EventRecord record : extra) {
            String key = record.event() + '|' + record.target() + '|'
                    + (record.origin() == null ? "" : record.origin().detail());
            QueryResult.RecordAggregation existing = groups.get(key);
            groups.put(key, existing == null
                    ? new QueryResult.RecordAggregation(record, 1)
                    : new QueryResult.RecordAggregation(existing.sample(), existing.count() + 1));
        }
        return List.copyOf(groups.values());
    }
}
