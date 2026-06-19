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

    /** Most-recent snapshots first, capped. Backs the {@code /sg inventory} GUI. */
    List<SalvageSnapshot> list(int limit);

    Optional<SalvageSnapshot> get(UUID id);

    /** Replace a snapshot's items after an extraction (the slots still present). */
    void replaceItems(UUID id, List<StoredItem> remaining);

    /** Remove an emptied snapshot. */
    void delete(UUID id);

    /** Best-effort release of any backend resources. */
    default void close() {
    }
}
