package net.medievalrp.spyglass.plugin.salvage;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.medievalrp.spyglass.api.event.StoredItem;
import org.jetbrains.annotations.ApiStatus;

/**
 * Durable store for container inventories a rollback destroyed. A dedicated
 * store (not the event log) because the rows are mutable — an operator extracts
 * items out of a snapshot over time — and carry full item blobs that have no
 * business in the searchable record stream. Dual-backend by the same pattern as
 * {@code UndoStack} / {@code ToolStateStore}.
 */
@ApiStatus.Internal
public interface SalvageStore {

    /** Persist a captured inventory. Called off the main thread. */
    void save(SalvageSnapshot snapshot);

    /** Most-recent snapshots first, capped. Used by the console/RCON listing. */
    List<SalvageSnapshot> list(int limit);

    /** Distinct rollbacks that still have unrecovered salvage, newest first.
     *  Backs the top level of the {@code /sg inventory} GUI. */
    List<SalvageStore.RollbackGroup> listRollbacks(int limit);

    /** Every salvage snapshot from one rollback, newest first. */
    List<SalvageSnapshot> listByRollback(UUID rollbackId, int limit);

    Optional<SalvageSnapshot> get(UUID id);

    /** Replace a snapshot's items after an extraction (the slots still present). */
    void replaceItems(UUID id, List<StoredItem> remaining);

    /** Remove an emptied snapshot. */
    void delete(UUID id);

    /** Best-effort release of any backend resources. */
    default void close() {
    }

    /** One rollback's salvage summary, for the index GUI (no item payloads). */
    record RollbackGroup(
            @org.bson.codecs.pojo.annotations.BsonProperty("_id") UUID rollbackId,
            int containerCount,
            String operatorName,
            java.time.Instant latest) {
    }
}
