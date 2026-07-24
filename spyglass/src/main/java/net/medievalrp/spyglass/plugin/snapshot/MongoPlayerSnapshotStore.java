package net.medievalrp.spyglass.plugin.snapshot;

import com.mongodb.MongoCommandException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.CreateCollectionOptions;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.Sorts;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import net.medievalrp.spyglass.api.event.StoredItem;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.bson.Document;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.types.Binary;
import org.jetbrains.annotations.ApiStatus;

/**
 * Mongo-backed {@link PlayerSnapshotStore}. Two collections decoded with the
 * shared Spyglass codec registry: {@code player_snapshots} holds one document
 * per capture with an embedded thin slot array (slot, count, and a 16-byte hash
 * ref - never the item bytes), and {@code snapshot_items} interns the item
 * payloads content-addressed by the {@code _id} hash. Modeled on
 * {@code MongoSalvageStore}; shares the record store's database and client.
 *
 * <p>There is no TTL index: {@link #prune} is the single retention mechanism
 * across every backend, and it also garbage-collects the intern rows a deleted
 * snapshot was the last to reference. Interning is a {@code replaceOne} upsert,
 * so re-interning a payload already on disk is a no-op that never throws.
 */
@ApiStatus.Internal
public final class MongoPlayerSnapshotStore implements PlayerSnapshotStore {

    private static final String SNAPSHOTS = "player_snapshots";
    private static final String ITEMS = "snapshot_items";
    private static final HexFormat HEX = HexFormat.of();

    private final MongoCollection<Document> snapshots;
    private final MongoCollection<Document> items;

    public MongoPlayerSnapshotStore(MongoDatabase database, CodecRegistry codecRegistry) {
        MongoDatabase db = database.withCodecRegistry(codecRegistry);
        ensureZstdCollection(db, SNAPSHOTS);
        ensureZstdCollection(db, ITEMS);
        this.snapshots = db.getCollection(SNAPSHOTS);
        this.items = db.getCollection(ITEMS);
        // (player asc, occurred desc) serves the only read: the newest capture
        // at or before T for one player. No expireAfter - prune() owns retention.
        this.snapshots.createIndex(Indexes.compoundIndex(
                Indexes.ascending("player"), Indexes.descending("occurred")));
    }

    @Override
    public void save(PlayerSnapshot snapshot) {
        List<Document> slotDocs = new ArrayList<>();
        Set<String> internedThisSave = new HashSet<>();
        for (SnapshotSlot slot : snapshot.slots()) {
            StoredItem item = slot.item();
            if (item == null || item.data() == null) {
                continue;
            }
            byte[] raw = Base64.getDecoder().decode(item.data());
            byte[] hash = hash16(raw);
            if (internedThisSave.add(HEX.formatHex(hash))) {
                // Upsert keyed by the content hash: a payload already on disk
                // is rewritten identically, so a double-intern cannot throw.
                items.replaceOne(Filters.eq("_id", new Binary(hash)),
                        new Document("_id", new Binary(hash))
                                .append("material", item.material())
                                .append("data", new Binary(raw)),
                        new ReplaceOptions().upsert(true));
            }
            slotDocs.add(new Document("slot", slot.slot())
                    .append("count", slot.count())
                    .append("hash", new Binary(hash)));
        }
        Document doc = new Document("_id", snapshot.id())
                .append("player", snapshot.player())
                .append("playerName", snapshot.playerName())
                .append("occurred", Date.from(snapshot.capturedAt()))
                .append("cause", snapshot.cause())
                .append("contentHash", snapshot.contentHash())
                .append("slots", slotDocs);
        // Upsert by _id so a re-save of the same capture id is idempotent,
        // matching the ClickHouse ReplacingMergeTree behaviour.
        snapshots.replaceOne(Filters.eq("_id", snapshot.id()), doc,
                new ReplaceOptions().upsert(true));
    }

    @Override
    public Optional<PlayerSnapshot> latestAtOrBefore(UUID player, Instant instant) {
        Document snap = snapshots.find(Filters.and(
                        Filters.eq("player", player),
                        Filters.lte("occurred", Date.from(instant))))
                .sort(Sorts.orderBy(Sorts.descending("occurred"), Sorts.descending("_id")))
                .first();
        if (snap == null) {
            return Optional.empty();
        }
        List<Document> slotDocs = snap.getList("slots", Document.class, List.of());
        Set<Binary> hashes = new HashSet<>();
        for (Document slotDoc : slotDocs) {
            hashes.add(slotDoc.get("hash", Binary.class));
        }
        Map<String, Document> blobs = fetchBlobs(hashes);

        List<SnapshotSlot> slots = new ArrayList<>(slotDocs.size());
        for (Document slotDoc : slotDocs) {
            Binary hash = slotDoc.get("hash", Binary.class);
            Document blob = blobs.get(HEX.formatHex(hash.getData()));
            if (blob == null) {
                // Referenced payload vanished (never expected: prune leaves
                // snapshot_items orphans, never live refs). Skip the slot.
                continue;
            }
            byte[] data = blob.get("data", Binary.class).getData();
            StoredItem item = new StoredItem(
                    slotDoc.getInteger("slot"), blob.getString("material"),
                    Base64.getEncoder().encodeToString(data), null, null, null, null);
            slots.add(new SnapshotSlot(slotDoc.getInteger("slot"),
                    slotDoc.getInteger("count"), item));
        }
        return Optional.of(new PlayerSnapshot(
                snap.get("_id", UUID.class),
                player,
                snap.getString("playerName"),
                snap.getDate("occurred").toInstant(),
                snap.getString("cause"),
                snap.getLong("contentHash"),
                slots));
    }

    @Override
    public OptionalLong lastContentHash(UUID player) {
        Document snap = snapshots.find(Filters.eq("player", player))
                .sort(Sorts.orderBy(Sorts.descending("occurred"), Sorts.descending("_id")))
                .projection(new Document("contentHash", 1))
                .first();
        if (snap == null) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(snap.getLong("contentHash"));
    }

    @Override
    public int prune(Instant cutoff) {
        long removed = snapshots.deleteMany(
                Filters.lt("occurred", Date.from(cutoff))).getDeletedCount();
        // Orphan-GC the intern table: gather every hash still referenced by a
        // surviving snapshot, then delete the payloads nothing points at. The
        // collections are small, so the anti-join is cheap at prune cadence.
        // An empty live set (every snapshot pruned) drops all payloads.
        List<Binary> live = new ArrayList<>();
        snapshots.distinct("slots.hash", Binary.class).into(live);
        items.deleteMany(Filters.nin("_id", live));
        return (int) removed;
    }

    private Map<String, Document> fetchBlobs(Set<Binary> hashes) {
        if (hashes.isEmpty()) {
            return Map.of();
        }
        Map<String, Document> blobs = new HashMap<>();
        for (Document blob : items.find(Filters.in("_id", hashes))) {
            blobs.put(HEX.formatHex(blob.get("_id", Binary.class).getData()), blob);
        }
        return blobs;
    }

    /** SHA-256/16 of the raw (base64-decoded) item payload. */
    private static byte[] hash16(byte[] raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw);
            return Arrays.copyOf(digest, 16);
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 is a required JCA algorithm on every JVM.
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    /**
     * Create a collection with WiredTiger's zstd block compressor when it does
     * not exist yet, ignoring NamespaceExists (48). Same rationale and race
     * handling as {@code MongoRecordStore}: the compressor is fixed at creation
     * time, so we create eagerly (first write would auto-create with the server
     * default) and let a concurrent creator win harmlessly.
     */
    private static void ensureZstdCollection(MongoDatabase database, String collection) {
        try {
            database.createCollection(collection, new CreateCollectionOptions()
                    .storageEngineOptions(new BsonDocument("wiredTiger",
                            new BsonDocument("configString", new BsonString("block_compressor=zstd")))));
        } catch (MongoCommandException ex) {
            if (ex.getErrorCode() != 48) {
                throw ex;
            }
        }
    }
}
