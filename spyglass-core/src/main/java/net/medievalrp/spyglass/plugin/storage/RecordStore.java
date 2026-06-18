package net.medievalrp.spyglass.plugin.storage;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import net.medievalrp.spyglass.api.event.EventRecord;
import net.medievalrp.spyglass.api.query.QueryRequest;
import net.medievalrp.spyglass.api.query.QueryResult;
import net.medievalrp.spyglass.api.rollback.RollbackEffect;
import net.medievalrp.spyglass.api.rollback.Rollbackable;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

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
     * Stream one page of {@code pageSize} records starting after
     * {@code cursor} (or from the beginning of the result set when
     * {@code cursor} is {@code null}). Used by the rollback path to
     * keep memory bounded — a 1M-row rollback can be processed in
     * pages of 5k rather than allocating the whole result list.
     *
     * <p>Implementations should use keyset pagination on
     * {@code (occurred, id)} so the per-page cost stays O(pageSize)
     * even deep in the result set — OFFSET-based pagination degrades
     * to O(N) per page on a large match set.
     *
     * <p>The returned {@link QueryPage#next} is {@code null} when the
     * result set is exhausted. Callers loop until {@code next} is
     * null OR the running tally hits {@code request.limit()}, whichever
     * comes first.
     *
     * <p>Default implementation falls back to a single full
     * {@link #query} call so test doubles and stores that haven't
     * been streaming-fitted still work — at the cost of materializing
     * everything once.
     */
    default QueryPage queryPage(QueryRequest request, QueryPage.Cursor cursor, int pageSize) {
        if (cursor != null) {
            return new QueryPage(List.of(), null);
        }
        QueryResult full = query(request);
        return new QueryPage(full.records(), null);
    }

    /** Per-record consumer for {@link #streamRollback}. */
    interface RecordSink {
        void accept(EventRecord record);
    }

    /**
     * Stream one read window of up to {@code windowLimit} rollback-shaped
     * records to {@code sink}, starting after {@code cursor} ({@code null}
     * = from the beginning), returning the cursor for the next window or
     * {@code null} when the result set is exhausted.
     *
     * <p>This is {@link #queryPage} without the list: the caller decides
     * what (if anything) stays resident per record, so the read window
     * can be sized for query efficiency alone — heap is bounded by
     * whatever the sink retains, not by {@code windowLimit} (#19).
     *
     * <p>Default implementation materializes one page via
     * {@link #queryPage} and replays it into the sink, so test doubles
     * and stores without a wire-streaming reader (Mongo) keep today's
     * behavior and memory profile.
     */
    default QueryPage.Cursor streamRollback(QueryRequest request, QueryPage.Cursor cursor,
                                            int windowLimit, RecordSink sink) {
        QueryPage page = queryPage(request, cursor, windowLimit);
        for (EventRecord record : page.records()) {
            sink.accept(record);
        }
        return page.next();
    }

    /**
     * Per-row consumer for {@link #streamRollbackEffects}. Split so the
     * common case — a simple block-replace — never allocates an effect
     * object on the hot path (#67): {@link #block} takes primitives and the
     * two block-data strings the apply engine actually reads, which the
     * caller folds straight into columnar arrays. Anything that needs more
     * (a tile-entity block, a container slot, an entity) arrives via
     * {@link #complex} as a built {@link RollbackEffect}; a matched-but-not
     * -rollbackable row arrives via {@link #skip}. Every method carries the
     * row's {@code occurred}/{@code id} so the caller can checkpoint its
     * cursor at window granularity.
     */
    interface RollbackEffectSink {
        /**
         * A simple (no tile-entity payload) single-block replace.
         *
         * @param blockData    the block-data string to write
         * @param expectedData the expected-current block-data for the #27
         *     state check, or {@code null} for no check
         */
        void block(UUID worldId, int x, int y, int z, String blockData,
                   @Nullable String expectedData, Instant occurred, UUID id);

        /** Any effect that isn't a simple block-replace (rare). */
        void complex(RollbackEffect effect, Instant occurred, UUID id);

        /** A matched row that is not rollbackable — advance the cursor only. */
        void skip(Instant occurred, UUID id);
    }

    /**
     * Stream a rollback read window as resolved {@link RollbackEffect}s
     * rather than {@link EventRecord}s (#67). This is the allocation-lean
     * path the rollback/undo engine uses: the only objects a backend must
     * build per row are the effect itself (location + the one or two block
     * snapshots / item / entity fields it needs) plus the cursor
     * coordinates — never the record wrapper, origin, source, server, or
     * target. On a million-row rollback that is the difference between
     * rebuilding a full forensic record graph per row and rebuilding only
     * what the apply engine reads, which keeps young-gen churn low enough
     * that windows die before Aikar's {@code MaxTenuringThreshold=1}
     * promotes them (the cause of the large-rollback GC freeze).
     *
     * @param rollback {@code true} to emit {@link Rollbackable#rollbackEffect()}
     *     (revert), {@code false} to emit {@link Rollbackable#restoreEffect()}
     *     (re-apply / undo-of-undo).
     *
     * <p>Default implementation rides {@link #streamRollback}: it builds the
     * full record and resolves the effect from it, so stores without a lean
     * decoder (Mongo, in-memory test doubles) keep working at today's
     * allocation profile. The ClickHouse backend overrides it with a
     * trimmed-column decode that never materializes the record.
     */
    default QueryPage.Cursor streamRollbackEffects(QueryRequest request, QueryPage.Cursor cursor,
                                                   int windowLimit, boolean rollback,
                                                   RollbackEffectSink sink) {
        return streamRollback(request, cursor, windowLimit, record -> {
            if (!(record instanceof Rollbackable rollbackable)) {
                sink.skip(record.occurred(), record.id());
                return;
            }
            RollbackEffect effect = rollback
                    ? rollbackable.rollbackEffect()
                    : rollbackable.restoreEffect();
            if (effect instanceof RollbackEffect.BlockReplace br
                    && br.replacement() != null && br.replacement().simple()) {
                net.medievalrp.spyglass.api.util.BlockLocation loc = br.location();
                String expected = br.expectedCurrent() == null ? null : br.expectedCurrent().blockData();
                sink.block(loc.worldId(), loc.x(), loc.y(), loc.z(),
                        br.replacement().blockData(), expected, record.occurred(), record.id());
            } else {
                sink.complex(effect, record.occurred(), record.id());
            }
        });
    }

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
