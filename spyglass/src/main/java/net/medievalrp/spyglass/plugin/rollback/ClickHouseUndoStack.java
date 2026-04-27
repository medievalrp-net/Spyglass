package net.medievalrp.spyglass.plugin.rollback;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.command.CommandResponse;
import com.clickhouse.client.api.query.GenericRecord;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.medievalrp.spyglass.api.rollback.RollbackEffect;
import net.medievalrp.spyglass.plugin.storage.BsonBlobs;
import org.jetbrains.annotations.ApiStatus;

/**
 * ClickHouse-backed {@link UndoStack}.
 *
 * <p>Backing table: {@code undo_history} (defined in
 * {@code ClickHouseSchema}). ReplacingMergeTree on
 * {@code (player_id, created_at, id)} so {@link #pop} can mark the
 * latest row deleted by inserting a {@code deleted = 1} replacement;
 * the merge engine collapses it on background merges, the TTL drops
 * old rows after 24 h, and live queries filter out rows where
 * {@code deleted = 1}.
 *
 * <p>The {@code inverse_effects} column stores the polymorphic
 * {@link RollbackEffect} list as a single base64-encoded
 * ZSTD-compressed BSON blob via {@link BsonBlobs}.
 */
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
    public void push(UUID playerId, String operationType, List<RollbackEffect> inverseEffects) {
        String effects = BsonBlobs.encodeRollbackEffectsBase64(inverseEffects);
        String sql = "INSERT INTO " + table
                + " (id, player_id, created_at, operation_type, inverse_effects, deleted) "
                + "VALUES (toUUID('" + UUID.randomUUID() + "'), "
                + "toUUID('" + playerId + "'), "
                + chTimestamp(Instant.now()) + ", "
                + escape(operationType) + ", "
                + escape(effects) + ", 0)";
        execute(sql);
    }

    @Override
    public Optional<UndoOperation> pop(UUID playerId) {
        String selectSql = "SELECT id, player_id, created_at, operation_type, inverse_effects "
                + "FROM " + table + " FINAL "
                + "WHERE player_id = toUUID('" + playerId + "') AND deleted = 0 "
                + "ORDER BY created_at DESC LIMIT 1";
        List<GenericRecord> rows = client.queryAll(selectSql);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        GenericRecord row = rows.get(0);
        UUID id = row.getUUID("id");
        UUID pid = row.getUUID("player_id");
        Instant createdAt = row.getInstant("created_at");
        String opType = row.getString("operation_type");
        List<RollbackEffect> effects = BsonBlobs.decodeRollbackEffectsBase64(
                row.getString("inverse_effects"));
        UndoOperation latest = new UndoOperation(id, pid, createdAt, opType, effects);

        // Tombstone via ReplacingMergeTree: insert the same primary key
        // with deleted=1; the merge engine collapses on next merge,
        // and FINAL on read filters it out immediately.
        String tombstoneSql = "INSERT INTO " + table
                + " (id, player_id, created_at, operation_type, inverse_effects, deleted) "
                + "VALUES (toUUID('" + latest.id() + "'), "
                + "toUUID('" + latest.playerId() + "'), "
                + chTimestamp(latest.createdAt()) + ", "
                + escape(latest.operationType()) + ", '', 1)";
        execute(tombstoneSql);
        return Optional.of(latest);
    }

    private void execute(String sql) {
        try (CommandResponse ignored = client.execute(sql).get(30, TimeUnit.SECONDS)) {
            // ack
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

    @SuppressWarnings("unused")
    private static LocalDateTime toLocalDateTime(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
