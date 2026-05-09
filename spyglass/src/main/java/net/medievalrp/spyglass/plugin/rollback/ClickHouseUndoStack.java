package net.medievalrp.spyglass.plugin.rollback;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.command.CommandResponse;
import com.clickhouse.client.api.query.GenericRecord;
import java.time.Instant;
import java.time.LocalDateTime;
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

/**
 * ClickHouse-backed {@link UndoStack}.
 *
 * <p>Backing table: {@code undo_history} (defined in
 * {@code ClickHouseSchema}). ReplacingMergeTree on
 * {@code (player_id, created_at, operation_id, chunk_index)} so {@link
 * #pop} can mark a row deleted by inserting a {@code deleted = 1}
 * replacement; the merge engine collapses on background merges, the
 * TTL drops old rows after 24 h, and live queries filter out rows
 * where {@code deleted = 1} via {@code FINAL}.
 *
 * <h2>Chunked rows</h2>
 *
 * One rollback's inverse-effects list is split across N rows that
 * share an {@code operation_id} and carry sequential {@code
 * chunk_index} values 0..N-1, plus the constant {@code chunk_count =
 * N} on every row of the operation. {@link #EFFECTS_PER_CHUNK} bounds
 * each row's BSON-encoded payload at ~20 MB, ensuring no individual
 * INSERT VALUES literal exceeds CH's parser-chunk allocation limit
 * (default 1 GiB on Pelican-managed instances).
 *
 * <p>Pre-chunked builds put the entire inverse list into a single
 * column value. At the 500k-effect undo cap the resulting BSON +
 * base64 + SQL escape pipeline tripped {@code MemoryLimitExceeded}
 * during {@code ValuesBlockInputFormat} parsing on the server.
 * Splitting into bounded rows leaves all other call paths
 * (RollbackService.push, UndoService.pop, undo apply) unchanged.
 *
 * <p>The {@code inverse_effects} column stores the polymorphic {@link
 * RollbackEffect} list as a single base64-encoded BSON blob via
 * {@link BsonBlobs}. (The {@code CODEC(ZSTD(1))} on the column lives
 * inside CH; the JVM-side blob is uncompressed base64.)
 */
@ApiStatus.Internal
public final class ClickHouseUndoStack implements UndoStack {

    /**
     * Maximum {@link RollbackEffect}s per row. The actual encoded
     * footprint per BlockReplace effect is ~600 B BSON (each carries
     * two BlockSnapshots with material strings + blockData strings),
     * so 25k × ~600 B ≈ 15 MB raw, ~20 MB base64, ~25 MB SQL string
     * after escape. Comfortably below CH's 1 GiB
     * {@code max_memory_usage} default on a Pelican egg.
     *
     * <p>The earlier 100k value produced 60 MB INSERT statements;
     * benchmarking showed the v2 client started failing with
     * {@code queryId = null} (request never reached CH) after ~7
     * consecutive 60 MB inserts during a 10-chunk push. Smaller
     * chunks at the cost of more round-trips trade peak send-buffer
     * pressure for reliability — the per-INSERT wall stays under
     * a second, so a 1 M-effect push fans out to 40 INSERTs in
     * roughly 30 s and never holds a multi-tens-of-MB buffer for
     * long.
     */
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
        // Always at least one row, even for empty effect lists, so a
        // pop() that lands on this op-id can return Optional.empty()
        // gracefully via the chunk-count check rather than skipping
        // the operation entirely.
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
        // Step 1: identify the latest operation. Pull metadata from
        // any one of its chunk rows (chunk_index = 0 is canonical, but
        // we order by chunk_index so we get a deterministic pick if
        // the index gets clipped). Skip pulling inverse_effects here
        // — that's a separate query so we don't stream the whole blob
        // twice.
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

        // Step 2: pull every chunk for this operation, ordered. CH
        // streams rows; client-v2's queryAll materializes them into a
        // List<GenericRecord> but each row's inverse_effects column is
        // bounded by EFFECTS_PER_CHUNK so we never allocate a multi-GB
        // single string in the JDBC layer. We also pull chunk_index so
        // the tombstone INSERT below can target the right keys without
        // a follow-up read.
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

        // Step 3: tombstone every chunk via a single multi-row VALUES
        // INSERT. We had `INSERT INTO ... SELECT ... FROM same_table`
        // here originally, which CH 24.8 silently no-ops when the
        // source table equals the destination — read_rows = 0,
        // written_rows = 0, no exception, just a phantom success.
        // (Reproducible at the clickhouse-client REPL; the same SQL
        // wrapped in `WITH cte AS (...) SELECT ... FROM cte` works.
        // We don't need the SELECT round-trip at all though — we
        // already have every primary-key field client-side from the
        // find query and chunkRows.)
        //
        // The tombstone payload is metadata-only (empty inverse_effects),
        // so the VALUES literal stays small even at chunk_count = 100
        // for a 10M-block rollback (~150 B per tuple, ~15 KB total).
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
