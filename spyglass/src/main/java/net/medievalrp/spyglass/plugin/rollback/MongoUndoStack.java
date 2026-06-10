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

// Mongo-backed UndoStack. Operations are stored as chunk documents of
// at most effectsPerChunk inverse effects each, sharing an operationId
// with chunkIndex 0..N-1 — a single-document operation hits Mongo's
// 16 MB document cap around ~70K effects, so whole-op documents cannot
// hold a large rollback at all.
//
// Streaming seal protocol (mirrors ClickHouseUndoStack): chunks
// 1..N-1 are inserted as the rollback streams with chunkCount=0;
// chunk 0 is withheld and only inserted by seal(), carrying the
// authoritative chunkCount. Readers recognise an operation solely by
// a chunk 0 with chunkCount > 0, so a crash mid-capture leaves
// documents no reader returns and the 24h TTL sweeps them.
//
// Legacy whole-op documents (the pre-chunk UndoOperation shape, no
// chunkIndex field) are still readable for the 24h they survive.
@ApiStatus.Internal
public final class MongoUndoStack implements UndoStack {

    private static final int DEFAULT_EFFECTS_PER_CHUNK = 25_000;

    // Chunk document. Public so the POJO/record codec can reach it.
    public record UndoChunk(
            @BsonProperty("_id") UUID id,
            UUID operationId,
            int chunkIndex,
            int chunkCount,
            UUID playerId,
            Instant createdAt,
            String operationType,
            List<RollbackEffect> effects) {
    }

    private final MongoCollection<UndoChunk> chunks;
    private final MongoCollection<UndoOperation> legacy;
    private final int effectsPerChunk;

    public MongoUndoStack(MongoDatabase database, CodecRegistry codecRegistry) {
        this(database, codecRegistry, DEFAULT_EFFECTS_PER_CHUNK);
    }

    // Visible chunk size for tests; production uses the default.
    public MongoUndoStack(MongoDatabase database, CodecRegistry codecRegistry, int effectsPerChunk) {
        MongoDatabase db = database.withCodecRegistry(codecRegistry);
        this.chunks = db.getCollection("UndoHistory", UndoChunk.class);
        this.legacy = db.getCollection("UndoHistory", UndoOperation.class);
        this.effectsPerChunk = Math.max(1, effectsPerChunk);
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
    public UndoWriter beginPush(UUID playerId, String operationType) {
        return new Writer(playerId, operationType);
    }

    private final class Writer extends ChunkedUndoWriter {

        private final UUID playerId;
        private final String operationType;
        private final UUID operationId = UUID.randomUUID();
        private final Instant createdAt = Instant.now();

        private Writer(UUID playerId, String operationType) {
            super(effectsPerChunk);
            this.playerId = playerId;
            this.operationType = operationType;
        }

        @Override
        protected void eraseOperation(int streamedChunks) {
            chunks.deleteMany(Filters.eq("operationId", operationId));
        }

        @Override
        protected void writeChunk(int chunkIndex, int chunkCount, List<RollbackEffect> effects) {
            chunks.insertOne(new UndoChunk(
                    UUID.randomUUID(), operationId, chunkIndex, chunkCount,
                    playerId, createdAt, operationType, List.copyOf(effects)));
        }
    }

    @Override
    public Optional<UndoReader> openLatest(UUID playerId) {
        // Newest sealed chunked head vs newest legacy whole-op doc —
        // whichever is more recent wins. Both queries are covered by
        // the (playerId, createdAt) index.
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
            return Optional.of(new LegacyReader(legacyOp));
        }
        if (head == null) {
            return Optional.empty();
        }
        return Optional.of(new Reader(head));
    }

    private final class Reader implements UndoReader {

        private final UndoChunk head;
        private int nextIndex = 0;

        private Reader(UndoChunk head) {
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

    private final class LegacyReader implements UndoReader {

        private final UndoOperation operation;
        private boolean served = false;

        private LegacyReader(UndoOperation operation) {
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
