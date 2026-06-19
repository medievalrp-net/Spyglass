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

    // Rollback indexes replaced by a newer shape below. createIndex can't
    // change an index in place (a different key spec is a different index,
    // not an update), so without dropping these an upgrade-in-place
    // deployment carries both the old and the new and pays the write-
    // amplification of maintaining the dead one on every insert. Exact-name
    // drops only ever touch indexes this class created; _id_, the current
    // rollback indexes, and any operator-made index are untouched.
    private static final List<String> SUPERSEDED_INDEXES = List.of(
            // Pre-#94 rollback indexes (no trailing id).
            "source.playerId_1_occurred_-1",
            "event_1_occurred_-1",
            "location.worldId_1_location.x_1_location.z_1_location.y_1_occurred_-1",
            // Block-coordinate location index, both the pre-id and id-covered
            // shapes, replaced by the chunk-bucketed (worldId, cx, cz, …) one.
            "location.worldId_1_location.x_1_location.z_1_location.y_1_occurred_-1_id_-1");

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
        // Chunk-bucketed: (worldId, cx, cz, occurred, id) rather than raw
        // (worldId, x, z, y, …). cx/cz (= x>>4, z>>4, written by
        // BlockLocationCodec) have a sixteenth of the range, so the index
        // prefix-compresses to roughly a quarter of the block-coord size
        // (~96 MiB -> ~25 MiB at 2M). PredicateToBson turns an x/z range into
        // a cx/cz range to seek this index, then the exact x/z/y bounds filter
        // within the seeked chunks. The trailing (occurred, id) keeps region
        // reads index-ordered, so the keyset still streams without a SORT.
        collection.createIndex(Indexes.compoundIndex(
                Indexes.ascending(RecordFields.LOCATION_WORLD_ID),
                Indexes.ascending(RecordFields.LOCATION_CX),
                Indexes.ascending(RecordFields.LOCATION_CZ),
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
