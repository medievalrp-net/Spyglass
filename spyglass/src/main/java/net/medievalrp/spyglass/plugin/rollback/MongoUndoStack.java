package net.medievalrp.spyglass.plugin.rollback;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Sorts;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.medievalrp.spyglass.api.rollback.RollbackEffect;
import org.bson.codecs.configuration.CodecRegistry;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class MongoUndoStack implements UndoStack {

    private final MongoCollection<UndoOperation> collection;

    public MongoUndoStack(MongoDatabase database, CodecRegistry codecRegistry) {
        this.collection = database.withCodecRegistry(codecRegistry)
                .getCollection("UndoHistory", UndoOperation.class);
        this.collection.createIndex(Indexes.compoundIndex(
                Indexes.ascending("playerId"),
                Indexes.descending("createdAt")));
        this.collection.createIndex(Indexes.ascending("createdAt"),
                new IndexOptions().expireAfter(24L, TimeUnit.HOURS));
    }

    @Override
    public void push(UUID playerId, String operationType, List<RollbackEffect> inverseEffects) {
        collection.insertOne(new UndoOperation(
                UUID.randomUUID(), playerId, Instant.now(),
                operationType, List.copyOf(inverseEffects)));
    }

    @Override
    public Optional<UndoOperation> pop(UUID playerId) {
        UndoOperation latest = collection.find(Filters.eq("playerId", playerId))
                .sort(Sorts.descending("createdAt"))
                .first();
        if (latest == null) {
            return Optional.empty();
        }
        collection.deleteOne(Filters.eq("_id", latest.id()));
        return Optional.of(latest);
    }
}
