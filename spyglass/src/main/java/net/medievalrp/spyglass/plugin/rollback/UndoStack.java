package net.medievalrp.spyglass.plugin.rollback;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.medievalrp.spyglass.api.rollback.RollbackEffect;
import org.bson.codecs.pojo.annotations.BsonProperty;
import org.jetbrains.annotations.ApiStatus;

// Per-player undo ledger. An undo operation is stored BY REFERENCE:
// one small row holding the resolved query that replays the original
// operation in the opposite direction (see UndoReferenceBson), plus a
// time ceiling. The event records themselves are the ledger — there
// is no inverse-effect capture, so recording an undo costs O(1)
// regardless of operation size, and the replay streams through the
// same engine pipeline as a rollback.
//
// Operations written by older builds — whole-operation documents and
// chunked inverse-effect rows — remain readable for the 24h they
// survive (both backends TTL the ledger), surfaced as LegacyOperation.
@ApiStatus.Internal
public interface UndoStack {

    // Suffix on the stored operation_type that marks a reference row.
    // Pre-reference rows never contain '#'.
    String REF_MARKER = "#REF";

    // Records a reference operation: one row, the blob produced by
    // UndoReferenceBson. operationType is the ORIGINAL op's mode name
    // (ROLLBACK / RESTORE); the marker is storage-internal.
    void pushReference(UUID playerId, String operationType, String referenceBase64);

    Optional<Popped> openLatest(UUID playerId);

    // The newest undoable operation for a player. tombstone() consumes
    // it — call only after a successful replay; a failed replay leaves
    // the operation poppable (force-overwrite applies make retry safe).
    sealed interface Popped permits ReplayReference, LegacyOperation {

        UUID operationId();

        Instant createdAt();

        // Marker-free: ROLLBACK / RESTORE.
        String operationType();

        void tombstone();

        void close();
    }

    // Reference operation: decode with UndoReferenceBson and replay
    // the stored request in the opposite mode.
    non-sealed interface ReplayReference extends Popped {

        String referenceBase64();
    }

    // Pre-reference operation: stream the stored inverse effects chunk
    // by chunk (chunk order == apply order). A store-side gap throws
    // IllegalStateException from nextChunk — report, don't half-apply.
    non-sealed interface LegacyOperation extends Popped {

        int chunkCount();

        Optional<List<RollbackEffect>> nextChunk();
    }

    // @BsonProperty is read by Mongo's POJO codec when decoding legacy
    // whole-operation documents; ClickHouse never sees this shape.
    record UndoOperation(
            @BsonProperty("_id") UUID id,
            UUID playerId,
            Instant createdAt,
            String operationType,
            List<RollbackEffect> inverseEffects) {
    }
}
