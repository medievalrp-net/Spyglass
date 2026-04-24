package net.medievalrp.spyglass.plugin.storage;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import org.bson.BsonDocument;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class IndexManager {

    public void ensureRecordIndexes(MongoCollection<BsonDocument> collection) {
        collection.createIndex(Indexes.compoundIndex(
                Indexes.ascending(RecordFields.SOURCE_PLAYER_ID),
                Indexes.descending(RecordFields.OCCURRED)));
        collection.createIndex(Indexes.compoundIndex(
                Indexes.ascending(RecordFields.EVENT),
                Indexes.descending(RecordFields.OCCURRED)));
        collection.createIndex(Indexes.compoundIndex(
                Indexes.ascending(RecordFields.LOCATION_WORLD_ID),
                Indexes.ascending(RecordFields.LOCATION_X),
                Indexes.ascending(RecordFields.LOCATION_Z),
                Indexes.ascending(RecordFields.LOCATION_Y),
                Indexes.descending(RecordFields.OCCURRED)));
        collection.createIndex(Indexes.ascending(RecordFields.EXPIRES_AT), new IndexOptions().expireAfter(0L, java.util.concurrent.TimeUnit.SECONDS));
    }
}
