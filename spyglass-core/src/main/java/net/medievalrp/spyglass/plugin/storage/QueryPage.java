package net.medievalrp.spyglass.plugin.storage;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import net.medievalrp.spyglass.api.event.EventRecord;
import org.jetbrains.annotations.ApiStatus;

/**
 * One page of records from a streaming query, plus the keyset cursor
 * the caller hands back to {@link RecordStore#queryPage} to fetch the
 * next page. {@link #next} is {@code null} when the result set is
 * exhausted.
 *
 * <p>Used by the streaming rollback path so memory stays {@code
 * O(pageSize)} instead of {@code O(totalRows)} — a 1M-row rollback
 * processes 200 pages of 5000 effects each rather than allocating a
 * single 1M-element list (which OOM'd on a 3 GB heap).
 */
@ApiStatus.Internal
public record QueryPage(List<EventRecord> records, Cursor next) {

    /**
     * Keyset position into the result set. {@code occurred} is the
     * timestamp of the last row in the previous page; {@code id} is its
     * UUID, used as a tiebreaker so rows sharing the exact same {@code
     * occurred} timestamp are still totally ordered. Pass {@code null}
     * to {@link RecordStore#queryPage} for the first page.
     */
    public record Cursor(Instant occurred, UUID id) {
    }
}
