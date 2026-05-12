package net.medievalrp.spyglass.plugin.rollback;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.medievalrp.spyglass.api.rollback.RollbackEffect;
import org.bson.codecs.pojo.annotations.BsonProperty;
import org.jetbrains.annotations.ApiStatus;

// Per-player rollback ledger. push() records inverse effects after a
// successful rollback; pop() drains the most recent op for /spyglass
// undo. Both Mongo and ClickHouse backends retain entries for 24h.
@ApiStatus.Internal
public interface UndoStack {

    void push(UUID playerId, String operationType, List<RollbackEffect> inverseEffects);

    Optional<UndoOperation> pop(UUID playerId);

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
