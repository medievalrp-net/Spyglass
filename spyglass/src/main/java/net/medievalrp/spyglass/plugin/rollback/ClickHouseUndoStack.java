package net.medievalrp.spyglass.plugin.rollback;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.command.CommandResponse;
import com.clickhouse.client.api.query.GenericRecord;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.medievalrp.spyglass.api.rollback.RollbackEffect;
import net.medievalrp.spyglass.plugin.storage.BsonBlobs;
import org.jetbrains.annotations.ApiStatus;

// ClickHouse-backed UndoStack. The undo_history table is a
// ReplacingMergeTree(deleted) keyed on (player_id, created_at,
// operation_id, chunk_index); tombstones are deleted=1 rows with the
// same key, and FINAL filters them out for reads. A 24h TTL trims old
// data.
//
// An operation's inverse effects are split across N rows sharing one
// operation_id with chunk_index 0..N-1, so no single INSERT literal
// blows past CH's parser-chunk allocation limit.
//
// Streaming seal protocol: chunks 1..N-1 are flushed as the rollback
// streams (chunk_count=0 — "unsealed"), while chunk 0 is withheld in
// the writer and only written by seal(), carrying the authoritative
// chunk_count. Readers recognise an operation solely by a chunk 0 row
// with chunk_count > 0, so a crash mid-capture leaves rows no reader
// returns (TTL sweeps them) — and every pre-streaming row ever written
// already satisfies the sealed predicate, so old ledgers stay
// readable. No same-key re-insert is ever needed, which matters
// because ReplacingMergeTree(deleted) only orders versions by the
// deleted flag.
@ApiStatus.Internal
public final class ClickHouseUndoStack implements UndoStack {

    // ~25k effects per row stays under ~25 MB SQL after BSON+base64
    // encoding. The previous 100k limit pushed past 60 MB and the
    // v2 client started dropping inserts silently.
    private static final int DEFAULT_EFFECTS_PER_CHUNK = 25_000;

    private static final DateTimeFormatter CH_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private final Client client;
    private final String table;
    private final int effectsPerChunk;

    public ClickHouseUndoStack(Client client, String database) {
        this(client, database, DEFAULT_EFFECTS_PER_CHUNK);
    }

    // Visible chunk size for tests; production uses the default.
    public ClickHouseUndoStack(Client client, String database, int effectsPerChunk) {
        this.client = client;
        this.table = "`" + database + "`.`undo_history`";
        this.effectsPerChunk = Math.max(1, effectsPerChunk);
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
            // Tombstone the streamed chunks (1..streamedChunks); chunk 0
            // was never written. Best-effort: the rows are unreachable
            // without a sealed head anyway and TTL out in 24h.
            tombstoneRows(operationId, playerId, createdAt, operationType,
                    1, streamedChunks + 1);
        }

        @Override
        protected void writeChunk(int chunkIndex, int chunkCount, List<RollbackEffect> effects) {
            String payload = BsonBlobs.encodeRollbackEffectsBase64(effects);
            String sql = "INSERT INTO " + table
                    + " (operation_id, chunk_index, chunk_count, "
                    + "player_id, created_at, operation_type, inverse_effects, deleted) "
                    + "VALUES (toUUID('" + operationId + "'), "
                    + chunkIndex + ", "
                    + chunkCount + ", "
                    + "toUUID('" + playerId + "'), "
                    + chTimestamp(createdAt) + ", "
                    + escape(operationType) + ", "
                    + escape(payload) + ", 0)";
            execute(sql);
        }
    }

    @Override
    public Optional<UndoReader> openLatest(UUID playerId) {
        // A sealed head is the operation's identity: chunk 0 with a
        // real chunk_count. Pre-streaming rows qualify by construction.
        String sql = "SELECT operation_id, created_at, operation_type, "
                + "chunk_count, inverse_effects "
                + "FROM " + table + " FINAL "
                + "WHERE player_id = toUUID('" + playerId + "') "
                + "  AND deleted = 0 AND chunk_index = 0 AND chunk_count > 0 "
                + "ORDER BY created_at DESC "
                + "LIMIT 1";
        List<GenericRecord> rows = client.queryAll(sql);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        GenericRecord head = rows.get(0);
        return Optional.of(new Reader(
                playerId,
                head.getUUID("operation_id"),
                head.getInstant("created_at"),
                head.getString("operation_type"),
                (int) head.getLong("chunk_count"),
                head.getString("inverse_effects")));
    }

    private final class Reader implements UndoReader {

        private final UUID playerId;
        private final UUID operationId;
        private final Instant createdAt;
        private final String operationType;
        private final int chunkCount;
        private String headPayload; // chunk 0, already fetched
        private int nextIndex = 0;

        private Reader(UUID playerId, UUID operationId, Instant createdAt,
                       String operationType, int chunkCount, String headPayload) {
            this.playerId = playerId;
            this.operationId = operationId;
            this.createdAt = createdAt;
            this.operationType = operationType;
            this.chunkCount = chunkCount;
            this.headPayload = headPayload;
        }

        @Override
        public UUID operationId() {
            return operationId;
        }

        @Override
        public Instant createdAt() {
            return createdAt;
        }

        @Override
        public String operationType() {
            return operationType;
        }

        @Override
        public int chunkCount() {
            return chunkCount;
        }

        @Override
        public Optional<List<RollbackEffect>> nextChunk() {
            if (nextIndex >= chunkCount) {
                return Optional.empty();
            }
            int index = nextIndex++;
            if (index == 0) {
                String payload = headPayload;
                headPayload = null;
                return Optional.of(BsonBlobs.decodeRollbackEffectsBase64(payload));
            }
            String sql = "SELECT inverse_effects FROM " + table + " FINAL "
                    + "WHERE operation_id = toUUID('" + operationId + "') "
                    + "  AND chunk_index = " + index + " AND deleted = 0 "
                    + "LIMIT 1";
            List<GenericRecord> rows = client.queryAll(sql);
            if (rows.isEmpty()) {
                throw new IllegalStateException("undo operation " + operationId
                        + " is missing chunk " + index + " of " + chunkCount);
            }
            return Optional.of(BsonBlobs.decodeRollbackEffectsBase64(
                    rows.get(0).getString("inverse_effects")));
        }

        @Override
        public void tombstone() {
            tombstoneRows(operationId, playerId, createdAt, operationType,
                    0, chunkCount);
        }

        @Override
        public void close() {
            headPayload = null;
        }
    }

    // Tombstones rows [fromIndex, toIndex) of an operation by inserting
    // deleted=1 rows with the same key; ReplacingMergeTree(deleted)
    // collapses them on merge and FINAL hides them immediately.
    private void tombstoneRows(UUID operationId, UUID playerId, Instant createdAt,
                               String operationType, int fromIndex, int toIndex) {
        if (fromIndex >= toIndex) {
            return;
        }
        String createdAtSql = chTimestamp(createdAt);
        String opTypeSql = escape(operationType);
        StringBuilder tombstone = new StringBuilder();
        tombstone.append("INSERT INTO ").append(table)
                .append(" (operation_id, chunk_index, chunk_count, ")
                .append("player_id, created_at, operation_type, inverse_effects, deleted) ")
                .append("VALUES ");
        for (int i = fromIndex; i < toIndex; i++) {
            if (i > fromIndex) {
                tombstone.append(", ");
            }
            tombstone.append("(toUUID('").append(operationId).append("'), ")
                    .append(i).append(", ")
                    .append(toIndex).append(", ")
                    .append("toUUID('").append(playerId).append("'), ")
                    .append(createdAtSql).append(", ")
                    .append(opTypeSql).append(", '', 1)");
        }
        execute(tombstone.toString());
    }

    private void execute(String sql) {
        try (CommandResponse ignored = client.execute(sql).get(30, TimeUnit.SECONDS)) {
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Undo command interrupted", ie);
        } catch (Exception ex) {
            throw new RuntimeException("Undo command failed: " + ex.getMessage(), ex);
        }
    }

    private static String chTimestamp(Instant instant) {
        return "'" + CH_TIMESTAMP.format(instant.atOffset(ZoneOffset.UTC)) + "'";
    }

    private static String escape(String value) {
        if (value == null) {
            return "''";
        }
        return "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }
}
