package net.medievalrp.spyglass.plugin.salvage;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Accumulators;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Sorts;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.medievalrp.spyglass.api.event.StoredItem;
import org.bson.codecs.configuration.CodecRegistry;
import org.jetbrains.annotations.ApiStatus;

/**
 * Mongo-backed {@link SalvageStore}. One small collection of
 * {@link SalvageSnapshot} documents, decoded with the shared Spyglass codec
 * registry (the same one that already knows {@code StoredItem}). Modeled on
 * {@code MongoUndoStack}.
 */
@ApiStatus.Internal
public final class MongoSalvageStore implements SalvageStore {

    private final MongoCollection<SalvageSnapshot> collection;

    public MongoSalvageStore(MongoDatabase database, CodecRegistry codecRegistry, long ttlDays) {
        MongoDatabase db = database.withCodecRegistry(codecRegistry);
        this.collection = db.getCollection("SpyglassSalvage", SalvageSnapshot.class);
        // Single ascending index on capturedAt: serves the newest-first list
        // (Mongo scans it in reverse) and, with expireAfter, prunes old
        // salvage so the store self-cleans. ttlDays <= 0 disables expiry.
        if (ttlDays > 0) {
            this.collection.createIndex(Indexes.ascending("capturedAt"),
                    new IndexOptions().expireAfter(ttlDays, TimeUnit.DAYS));
        } else {
            this.collection.createIndex(Indexes.ascending("capturedAt"));
        }
    }

    @Override
    public void save(SalvageSnapshot snapshot) {
        collection.insertOne(snapshot);
    }

    @Override
    public List<SalvageSnapshot> list(int limit) {
        List<SalvageSnapshot> out = new ArrayList<>();
        collection.find()
                .sort(Sorts.descending("capturedAt"))
                .limit(Math.max(1, limit))
                .into(out);
        return out;
    }

    @Override
    public List<RollbackGroup> listRollbacks(int limit) {
        List<RollbackGroup> out = new ArrayList<>();
        collection.aggregate(List.of(
                        Aggregates.group("$rollbackOpId",
                                Accumulators.sum("containerCount", 1),
                                Accumulators.first("operatorName", "$operatorName"),
                                Accumulators.max("latest", "$capturedAt")),
                        Aggregates.sort(Sorts.descending("latest")),
                        Aggregates.limit(Math.max(1, limit))),
                        RollbackGroup.class)
                .into(out);
        return out;
    }

    @Override
    public List<SalvageSnapshot> listByRollback(UUID rollbackId, int limit) {
        List<SalvageSnapshot> out = new ArrayList<>();
        collection.find(Filters.eq("rollbackOpId", rollbackId))
                .sort(Sorts.descending("capturedAt"))
                .limit(Math.max(1, limit))
                .into(out);
        return out;
    }

    @Override
    public Optional<SalvageSnapshot> get(UUID id) {
        return Optional.ofNullable(collection.find(Filters.eq("_id", id)).first());
    }

    @Override
    public void replaceItems(UUID id, List<StoredItem> remaining) {
        SalvageSnapshot current = collection.find(Filters.eq("_id", id)).first();
        if (current == null) {
            return;
        }
        collection.replaceOne(Filters.eq("_id", id), current.withItems(remaining));
    }

    @Override
    public void delete(UUID id) {
        collection.deleteOne(Filters.eq("_id", id));
    }
}
