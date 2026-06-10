package net.medievalrp.spyglass.plugin.rollback;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.medievalrp.spyglass.api.rollback.RollbackEffect;
import org.bson.codecs.pojo.annotations.BsonProperty;
import org.jetbrains.annotations.ApiStatus;

// Per-player rollback ledger. Capture and replay are both streamed so
// neither side ever holds a whole operation's inverse effects in heap:
// the rollback appends inverses page by page through an UndoWriter, and
// /spyglass undo replays chunk by chunk through an UndoReader. Both
// Mongo and ClickHouse backends retain entries for 24h.
//
// Crash contract: an operation only becomes visible to readers once
// seal() succeeds. A writer closed without seal() tombstones whatever
// it wrote; a process death mid-stream leaves chunks that no reader
// will return (no sealed head chunk) and the 24h TTL sweeps them.
@ApiStatus.Internal
public interface UndoStack {

    // Streaming capture for one operation. Not thread-safe; the
    // rollback's page loop is the only writer.
    interface UndoWriter extends AutoCloseable {

        // Appends the next slice of inverse effects, in apply order.
        // May flush full chunks to the store; bounded memory.
        void append(List<RollbackEffect> effects);

        // Effects appended so far (for cap accounting / reporting).
        long appended();

        // Publishes the operation: flushes remaining effects and writes
        // the sealed head chunk that makes the op visible to readers.
        void seal();

        // Tombstones anything already written; close() without seal()
        // does the same. Idempotent.
        void abandon();

        @Override
        void close();
    }

    // Streaming replay of one sealed operation, oldest chunk first
    // (chunk order == capture order == apply order).
    interface UndoReader extends AutoCloseable {

        UUID operationId();

        Instant createdAt();

        String operationType();

        // Total chunks recorded at seal time.
        int chunkCount();

        // Next chunk of inverse effects, or empty when exhausted.
        // A store-side gap (missing chunk) throws IllegalStateException
        // — the op is corrupt and the caller should report, not apply.
        Optional<List<RollbackEffect>> nextChunk();

        // Consumes the operation so the next pop sees the one below it.
        // Call after a successful replay — a failed replay leaves the
        // op poppable again (force-overwrite apply makes retry safe).
        void tombstone();

        @Override
        void close();
    }

    UndoWriter beginPush(UUID playerId, String operationType);

    Optional<UndoReader> openLatest(UUID playerId);

    // Whole-list convenience used by small ops and tests; bounded
    // callers only — large operations stream via beginPush directly.
    default void push(UUID playerId, String operationType, List<RollbackEffect> inverseEffects) {
        try (UndoWriter writer = beginPush(playerId, operationType)) {
            writer.append(inverseEffects);
            writer.seal();
        }
    }

    // Whole-list convenience mirror of openLatest; materializes every
    // chunk, so only safe where the operation is known to be small.
    default Optional<UndoOperation> pop(UUID playerId) {
        Optional<UndoReader> opened = openLatest(playerId);
        if (opened.isEmpty()) {
            return Optional.empty();
        }
        try (UndoReader reader = opened.get()) {
            List<RollbackEffect> all = new ArrayList<>();
            for (Optional<List<RollbackEffect>> chunk = reader.nextChunk();
                    chunk.isPresent(); chunk = reader.nextChunk()) {
                all.addAll(chunk.get());
            }
            UndoOperation operation = new UndoOperation(
                    reader.operationId(), playerId, reader.createdAt(),
                    reader.operationType(), all);
            reader.tombstone();
            return Optional.of(operation);
        }
    }

    // @BsonProperty is read by Mongo's POJO codec; the ClickHouse
    // backend builds the record from columns and ignores it.
    record UndoOperation(
            @BsonProperty("_id") UUID id,
            UUID playerId,
            Instant createdAt,
            String operationType,
            List<RollbackEffect> inverseEffects) {
    }
}
