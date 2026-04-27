package net.medievalrp.spyglass.plugin.rollback;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.medievalrp.spyglass.api.rollback.RollbackEffect;
import org.bson.codecs.pojo.annotations.BsonProperty;
import org.jetbrains.annotations.ApiStatus;

/**
 * Per-player rollback ledger.
 *
 * <p>The plugin invokes {@link #push} every time a rollback / restore
 * operation lands successfully, recording the inverse effects so the
 * player can issue {@code /sg undo} and walk one step back. {@link
 * #pop} returns the most recent operation for that player and removes
 * it from the ledger. Implementations choose their own retention
 * window (24 h for the default Mongo and ClickHouse backends).
 *
 * <p>Two implementations: {@link MongoUndoStack} for Mongo
 * deployments and {@link ClickHouseUndoStack} for ClickHouse
 * deployments. The plugin picks one at startup based on
 * {@code database.backend}.
 */
@ApiStatus.Internal
public interface UndoStack {

    void push(UUID playerId, String operationType, List<RollbackEffect> inverseEffects);

    Optional<UndoOperation> pop(UUID playerId);

    /** {@code @BsonProperty} stays on the type-safe record because the
     * Mongo backend's POJO codec uses it. The ClickHouse backend
     * builds the record directly from columns and ignores the
     * annotation. */
    record UndoOperation(
            @BsonProperty("_id") UUID id,
            UUID playerId,
            Instant createdAt,
            String operationType,
            List<RollbackEffect> inverseEffects) {
    }
}
