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
// Reference operations (the only kind written since #17 settled on
// undo-by-reference) are a single row: chunk_index=0, chunk_count=1,
// operation_type='<MODE>#REF', inverse_effects = the reference blob.
// The row is ~1KB, so the plain VALUES insert is fine — the RowBinary
// machinery this file briefly carried existed only for multi-MB
// inverse-effect payloads, which no longer exist.
//
// Legacy rows remain readable for the 24h they survive: chunked
// operations (chunk_index 0..N-1 sharing an operation_id) and the
// pre-chunk whole-operation rows (which satisfy the same head
// predicate: chunk 0 with chunk_count > 0).
@ApiStatus.Internal
public final class ClickHouseUndoStack implements UndoStack {

    private static final DateTimeFormatter CH_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private final Client client;
    private final String table;

    public ClickHouseUndoStack(Client client, String database) {
        this.client = client;
        this.table = "`" + database + "`.`undo_history`";
    }

    @Override
    public void pushReference(UUID playerId, String operationType, String referenceBase64) {
        UUID operationId = UUID.randomUUID();
        String sql = "INSERT INTO " + table
                + " (operation_id, chunk_index, chunk_count, "
                + "player_id, created_at, operation_type, inverse_effects, deleted) "
                + "VALUES (toUUID('" + operationId + "'), 0, 1, "
                + "toUUID('" + playerId + "'), "
                + chTimestamp(Instant.now()) + ", "
                + escape(operationType + REF_MARKER) + ", "
                + escape(referenceBase64) + ", 0)";
        execute(sql);
    }

    @Override
    public Optional<Popped> openLatest(UUID playerId) {
        // The head row is the operation's identity: chunk 0 with a real
        // chunk_count. References, chunked ops, and pre-chunk whole ops
        // all satisfy it.
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
        UUID operationId = head.getUUID("operation_id");
        Instant createdAt = head.getInstant("created_at");
        String storedType = head.getString("operation_type");
        int chunkCount = (int) head.getLong("chunk_count");
        String headPayload = head.getString("inverse_effects");
        if (storedType.endsWith(REF_MARKER)) {
            String type = storedType.substring(0, storedType.length() - REF_MARKER.length());
            return Optional.of(new Reference(
                    playerId, operationId, createdAt, type, headPayload));
        }
        return Optional.of(new LegacyChunks(
                playerId, operationId, createdAt, storedType, chunkCount, headPayload));
    }

    private final class Reference implements ReplayReference {

        private final UUID playerId;
        private final UUID operationId;
        private final Instant createdAt;
        private final String operationType;
        private String reference;

        private Reference(UUID playerId, UUID operationId, Instant createdAt,
                          String operationType, String reference) {
            this.playerId = playerId;
            this.operationId = operationId;
            this.createdAt = createdAt;
            this.operationType = operationType;
            this.reference = reference;
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
        public String referenceBase64() {
            return reference;
        }

        @Override
        public void tombstone() {
            tombstoneRows(operationId, playerId, createdAt,
                    operationType + REF_MARKER, 0, 1);
        }

        @Override
        public void close() {
            reference = null;
        }
    }

    private final class LegacyChunks implements LegacyOperation {

        private final UUID playerId;
        private final UUID operationId;
        private final Instant createdAt;
        private final String operationType;
        private final int chunkCount;
        private String headPayload; // chunk 0, already fetched
        private int nextIndex = 0;

        private LegacyChunks(UUID playerId, UUID operationId, Instant createdAt,
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
