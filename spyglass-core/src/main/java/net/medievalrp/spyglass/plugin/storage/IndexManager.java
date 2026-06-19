package net.medievalrp.spyglass.plugin.storage;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.bson.BsonDocument;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class IndexManager {

    // The pre-id rollback indexes, superseded by the (… , occurred desc,
    // id desc) shape below. Each new index is a strict superset of its
    // legacy form (same prefix, one extra trailing field), so once the new
    // one exists the old is pure write-amplification — every record insert
    // would maintain both. createIndex can't replace them in place (adding
    // id changes the generated name, so it's a new index, not an update), so
    // an upgrade-in-place deployment is left carrying both until we drop the
    // legacy names here. Exact-name drops only ever touch indexes this class
    // created; _id_, the new rollback indexes, and any operator-made index
    // are untouched.
    private static final List<String> SUPERSEDED_INDEXES = List.of(
            "source.playerId_1_occurred_-1",
            "event_1_occurred_-1",
            "location.worldId_1_location.x_1_location.z_1_location.y_1_occurred_-1");

    public void ensureRecordIndexes(MongoCollection<BsonDocument> collection) {
        // Each rollback index ends with (occurred desc, id desc) — the exact
        // key the keyset reader (queryPage / streamRollbackEffects) sorts by.
        // Without the trailing id, the (occurred, id) sort is only partly
        // index-ordered, so Mongo adds a blocking in-memory SORT stage per
        // 500k-row page (plan: SORT->FETCH->IXSCAN). Including id makes the
        // read a pure streamed scan (LIMIT->FETCH->IXSCAN, no SORT), which on
        // a 2M rollback is the difference between ~12s and ~4s of read with
        // no extra heap — the keyset was designed for this index shape. The
        // index covers both directions (NEWEST_FIRST forward, OLDEST_FIRST via
        // a backward scan).
        collection.createIndex(Indexes.compoundIndex(
                Indexes.ascending(RecordFields.SOURCE_PLAYER_ID),
                Indexes.descending(RecordFields.OCCURRED),
                Indexes.descending(RecordFields.ID)));
        collection.createIndex(Indexes.compoundIndex(
                Indexes.ascending(RecordFields.EVENT),
                Indexes.descending(RecordFields.OCCURRED),
                Indexes.descending(RecordFields.ID)));
        collection.createIndex(Indexes.compoundIndex(
                Indexes.ascending(RecordFields.LOCATION_WORLD_ID),
                Indexes.ascending(RecordFields.LOCATION_X),
                Indexes.ascending(RecordFields.LOCATION_Z),
                Indexes.ascending(RecordFields.LOCATION_Y),
                Indexes.descending(RecordFields.OCCURRED),
                Indexes.descending(RecordFields.ID)));
        collection.createIndex(Indexes.ascending(RecordFields.EXPIRES_AT),
                new IndexOptions().expireAfter(0L, TimeUnit.SECONDS));

        dropSupersededIndexes(collection);
    }

    private void dropSupersededIndexes(MongoCollection<BsonDocument> collection) {
        Set<String> present = new HashSet<>();
        for (BsonDocument index : collection.listIndexes(BsonDocument.class)) {
            present.add(index.getString("name").getValue());
        }
        for (String legacy : SUPERSEDED_INDEXES) {
            if (present.contains(legacy)) {
                collection.dropIndex(legacy);
            }
        }
    }
}
