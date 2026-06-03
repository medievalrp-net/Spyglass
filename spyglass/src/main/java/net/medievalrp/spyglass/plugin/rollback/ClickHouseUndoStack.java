package net.medievalrp.spyglass.plugin.rollback;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.command.CommandResponse;
import com.clickhouse.client.api.query.GenericRecord;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.medievalrp.spyglass.api.rollback.RollbackEffect;
import net.medievalrp.spyglass.plugin.storage.BsonBlobs;
import org.jetbrains.annotations.ApiStatus;

// ClickHouse-backed UndoStack. The undo_history table is a
// ReplacingMergeTree keyed on (player_id, created_at, operation_id,
// chunk_index); pop marks rows deleted by inserting a deleted=1
// row, and FINAL filters them out for reads. A 24h TTL trims old
// data.
//
// A rollback's inverse effects are split across N rows sharing one
// operation_id with sequential chunk_index values, so no single
// INSERT literal blows past CH's parser-chunk allocation limit.
@ApiStatus.Internal
public final class ClickHouseUndoStack implements UndoStack {

    // ~25k effects per row stays under ~25 MB SQL after BSON+base64
    // encoding. The previous 100k limit pushed past 60 MB and the
    // v2 client started dropping inserts silently.
    private static final int EFFECTS_PER_CHUNK = 25_000;

    private static final DateTimeFormatter CH_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private final Client client;
    private final String table;

    public ClickHouseUndoStack(Client client, String database) {
        this.client = client;
        this.table = "`" + database + "`.`undo_history`";
    }

    @Override
    public void push(UUID playerId, String operationType, List<RollbackEffect> inverseEffects) {
        UUID operationId = UUID.randomUUID();
        Instant now = Instant.now();
        int total = inverseEffects.size();
        // Always write at least one row even for empty effect lists,
        // so pop() can find the op and report it cleanly.
        int chunkCount = total == 0 ? 1 : (total + EFFECTS_PER_CHUNK - 1) / EFFECTS_PER_CHUNK;
        for (int i = 0; i < chunkCount; i++) {
            int from = i * EFFECTS_PER_CHUNK;
            int to = Math.min(from + EFFECTS_PER_CHUNK, total);
            List<RollbackEffect> slice = total == 0 ? List.of() : inverseEffects.subList(from, to);
            String effects = BsonBlobs.encodeRollbackEffectsBase64(slice);
            String sql = "INSERT INTO " + table
                    + " (operation_id, chunk_index, chunk_count, "
                    + "player_id, created_at, operation_type, inverse_effects, deleted) "
                    + "VALUES (toUUID('" + operationId + "'), "
                    + i + ", "
                    + chunkCount + ", "
                    + "toUUID('" + playerId + "'), "
                    + chTimestamp(now) + ", "
                    + escape(operationType) + ", "
                    + escape(effects) + ", 0)";
            execute(sql);
        }
    }

    @Override
    public Optional<UndoOperation> pop(UUID playerId) {
        // Find the latest op-id; metadata only, no payload.
        String findSql = "SELECT operation_id, created_at, operation_type "
                + "FROM " + table + " FINAL "
                + "WHERE player_id = toUUID('" + playerId + "') AND deleted = 0 "
                + "ORDER BY created_at DESC, operation_id, chunk_index ASC "
                + "LIMIT 1";
        List<GenericRecord> latestRows = client.queryAll(findSql);
        if (latestRows.isEmpty()) {
            return Optional.empty();
        }
        GenericRecord meta = latestRows.get(0);
        UUID operationId = meta.getUUID("operation_id");
        Instant createdAt = meta.getInstant("created_at");
        String opType = meta.getString("operation_type");

        // Pull every chunk row for the op in order. Each row's
        // payload is bounded by EFFECTS_PER_CHUNK so this never
        // materializes a multi-GB string client-side.
        String chunksSql = "SELECT chunk_index, inverse_effects "
                + "FROM " + table + " FINAL "
                + "WHERE operation_id = toUUID('" + operationId + "') AND deleted = 0 "
                + "ORDER BY chunk_index ASC";
        List<GenericRecord> chunkRows = client.queryAll(chunksSql);
        List<RollbackEffect> all = new ArrayList<>();
        List<Long> chunkIndices = new ArrayList<>(chunkRows.size());
        for (GenericRecord row : chunkRows) {
            chunkIndices.add(row.getLong("chunk_index"));
            all.addAll(BsonBlobs.decodeRollbackEffectsBase64(
                    row.getString("inverse_effects")));
        }
        UndoOperation latest = new UndoOperation(
                operationId, playerId, createdAt, opType, all);

        // Tombstone every chunk in one multi-row VALUES INSERT.
        // CH 24.8 silently no-ops INSERT...SELECT when source and
        // destination are the same table (no rows written, no error),
        // so build the values literal client-side instead.
        if (!chunkIndices.isEmpty()) {
            int chunkCount = chunkIndices.size();
            String createdAtSql = chTimestamp(createdAt);
            String opTypeSql = escape(opType);
            StringBuilder tombstone = new StringBuilder();
            tombstone.append("INSERT INTO ").append(table)
                    .append(" (operation_id, chunk_index, chunk_count, ")
                    .append("player_id, created_at, operation_type, inverse_effects, deleted) ")
                    .append("VALUES ");
            for (int i = 0; i < chunkIndices.size(); i++) {
                if (i > 0) {
                    tombstone.append(", ");
                }
                tombstone.append("(toUUID('").append(operationId).append("'), ")
                        .append(chunkIndices.get(i)).append(", ")
                        .append(chunkCount).append(", ")
                        .append("toUUID('").append(playerId).append("'), ")
                        .append(createdAtSql).append(", ")
                        .append(opTypeSql).append(", '', 1)");
            }
            execute(tombstone.toString());
        }
        return Optional.of(latest);
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
