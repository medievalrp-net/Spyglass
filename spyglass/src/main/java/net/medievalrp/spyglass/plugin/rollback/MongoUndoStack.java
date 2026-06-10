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
import org.bson.codecs.pojo.annotations.BsonProperty;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

// Mongo-backed UndoStack. Reference operations (the only kind written
// since #17 settled on undo-by-reference) are one small document:
// chunkIndex=0, chunkCount=1, operationType='<MODE>#REF', reference =
// the blob, effects empty.
//
// Legacy documents remain readable for the 24h they survive: chunked
// inverse-effect documents (same UndoChunk shape, reference null) and
// the pre-chunk whole-operation UndoOperation documents (no chunkIndex
// field at all).
@ApiStatus.Internal
public final class MongoUndoStack implements UndoStack {

    // Ledger document. Public so the record codec can reach it.
    // `reference` is null on legacy chunk documents (and absent on
    // ones written before the field existed — the codec decodes a
    // missing field as null).
    public record UndoChunk(
            @BsonProperty("_id") UUID id,
            UUID operationId,
            int chunkIndex,
            int chunkCount,
            UUID playerId,
            Instant createdAt,
            String operationType,
            List<RollbackEffect> effects,
            @Nullable String reference) {
    }

    private final MongoCollection<UndoChunk> chunks;
    private final MongoCollection<UndoOperation> legacy;

    public MongoUndoStack(MongoDatabase database, CodecRegistry codecRegistry) {
        MongoDatabase db = database.withCodecRegistry(codecRegistry);
        this.chunks = db.getCollection("UndoHistory", UndoChunk.class);
        this.legacy = db.getCollection("UndoHistory", UndoOperation.class);
        this.chunks.createIndex(Indexes.compoundIndex(
                Indexes.ascending("playerId"),
                Indexes.descending("createdAt")));
        this.chunks.createIndex(Indexes.compoundIndex(
                Indexes.ascending("operationId"),
                Indexes.ascending("chunkIndex")));
        this.chunks.createIndex(Indexes.ascending("createdAt"),
                new IndexOptions().expireAfter(24L, TimeUnit.HOURS));
    }

    @Override
    public void pushReference(UUID playerId, String operationType, String referenceBase64) {
        chunks.insertOne(new UndoChunk(
                UUID.randomUUID(), UUID.randomUUID(), 0, 1,
                playerId, Instant.now(), operationType + REF_MARKER,
                List.of(), referenceBase64));
    }

    @Override
    public Optional<Popped> openLatest(UUID playerId) {
        // Newest head document (reference or chunked) vs newest legacy
        // whole-op document — whichever is more recent wins. Both
        // queries are covered by the (playerId, createdAt) index.
        UndoChunk head = chunks.find(Filters.and(
                        Filters.eq("playerId", playerId),
                        Filters.eq("chunkIndex", 0),
                        Filters.gt("chunkCount", 0)))
                .sort(Sorts.descending("createdAt"))
                .first();
        UndoOperation legacyOp = legacy.find(Filters.and(
                        Filters.eq("playerId", playerId),
                        Filters.exists("chunkIndex", false),
                        Filters.exists("inverseEffects", true)))
                .sort(Sorts.descending("createdAt"))
                .first();
        boolean useLegacy = legacyOp != null
                && (head == null || legacyOp.createdAt().isAfter(head.createdAt()));
        if (useLegacy) {
            return Optional.of(new LegacyWholeOp(legacyOp));
        }
        if (head == null) {
            return Optional.empty();
        }
        if (head.operationType().endsWith(REF_MARKER)) {
            return Optional.of(new Reference(head));
        }
        return Optional.of(new LegacyChunks(head));
    }

    private final class Reference implements ReplayReference {

        private final UndoChunk head;

        private Reference(UndoChunk head) {
            this.head = head;
        }

        @Override
        public UUID operationId() {
            return head.operationId();
        }

        @Override
        public Instant createdAt() {
            return head.createdAt();
        }

        @Override
        public String operationType() {
            String stored = head.operationType();
            return stored.substring(0, stored.length() - REF_MARKER.length());
        }

        @Override
        public String referenceBase64() {
            return head.reference();
        }

        @Override
        public void tombstone() {
            chunks.deleteMany(Filters.eq("operationId", head.operationId()));
        }

        @Override
        public void close() {
        }
    }

    private final class LegacyChunks implements LegacyOperation {

        private final UndoChunk head;
        private int nextIndex = 0;

        private LegacyChunks(UndoChunk head) {
            this.head = head;
        }

        @Override
        public UUID operationId() {
            return head.operationId();
        }

        @Override
        public Instant createdAt() {
            return head.createdAt();
        }

        @Override
        public String operationType() {
            return head.operationType();
        }

        @Override
        public int chunkCount() {
            return head.chunkCount();
        }

        @Override
        public Optional<List<RollbackEffect>> nextChunk() {
            if (nextIndex >= head.chunkCount()) {
                return Optional.empty();
            }
            int index = nextIndex++;
            if (index == 0) {
                return Optional.of(head.effects());
            }
            UndoChunk chunk = chunks.find(Filters.and(
                            Filters.eq("operationId", head.operationId()),
                            Filters.eq("chunkIndex", index)))
                    .first();
            if (chunk == null) {
                throw new IllegalStateException("undo operation " + head.operationId()
                        + " is missing chunk " + index + " of " + head.chunkCount());
            }
            return Optional.of(chunk.effects());
        }

        @Override
        public void tombstone() {
            chunks.deleteMany(Filters.eq("operationId", head.operationId()));
        }

        @Override
        public void close() {
        }
    }

    private final class LegacyWholeOp implements LegacyOperation {

        private final UndoOperation operation;
        private boolean served = false;

        private LegacyWholeOp(UndoOperation operation) {
            this.operation = operation;
        }

        @Override
        public UUID operationId() {
            return operation.id();
        }

        @Override
        public Instant createdAt() {
            return operation.createdAt();
        }

        @Override
        public String operationType() {
            return operation.operationType();
        }

        @Override
        public int chunkCount() {
            return 1;
        }

        @Override
        public Optional<List<RollbackEffect>> nextChunk() {
            if (served) {
                return Optional.empty();
            }
            served = true;
            return Optional.of(operation.inverseEffects());
        }

        @Override
        public void tombstone() {
            legacy.deleteOne(Filters.eq("_id", operation.id()));
        }

        @Override
        public void close() {
        }
    }
}
