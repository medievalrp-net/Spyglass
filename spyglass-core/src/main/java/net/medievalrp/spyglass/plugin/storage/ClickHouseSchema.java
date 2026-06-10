package net.medievalrp.spyglass.plugin.storage;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.command.CommandResponse;
import com.clickhouse.client.api.command.CommandSettings;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.jetbrains.annotations.ApiStatus;

/**
 * DDL for the ClickHouse-backed Spyglass deployment.
 *
 * <p>Three tables in one database:
 *
 * <ul>
 *   <li>{@code event_records} — the primary forensic event log.
 *       ReplacingMergeTree keyed on {@code (event, occurred, id)} so
 *       WAL replay after a mid-batch crash deduplicates automatically
 *       on the next part merge; monthly partitions, {@code by_player}
 *       projection for player-scoped lookups, TTL on
 *       {@code expires_at}. Reads see at-most-one duplicate per
 *       record id during the merge window — see {@link
 *       net.medievalrp.spyglass.plugin.pipeline.WalDurability}
 *       for the recovery contract.</li>
 *   <li>{@code undo_history} — per-player rollback ledger. MergeTree
 *       sorted by {@code (player_id, created_at)} for efficient pop;
 *       1-day TTL.</li>
 *   <li>{@code tool_states} — wand-enabled players. EmbeddedRocksDB
 *       engine: a CH table backed by RocksDB key-value, designed
 *       exactly for "small mutable lookup table next to a MergeTree
 *       analytics table".</li>
 * </ul>
 *
 * <h2>event_records column strategy</h2>
 *
 * Wide flat schema. Every filterable or displayable scalar across the
 * 17 EventRecord subtypes gets its own typed column; columns
 * irrelevant to a given record type are NULL. ClickHouse's Nullable
 * + LowCardinality compression makes the sparseness essentially free.
 *
 * <p>The deeply-nested fields ({@link
 * net.medievalrp.spyglass.api.event.BlockSnapshot},
 * {@link net.medievalrp.spyglass.api.event.StoredItem}) are
 * stored as opaque ZSTD-compressed BSON byte strings via {@link
 * BsonBlobs}. The Mongo codec hierarchy already knows how to
 * encode/decode them, and CH never has to look inside the bytes —
 * predicates that target item names / lore / enchants stay on the
 * Mongo backend (an explicit limitation of the CH backend).
 *
 * <p>{@link #ensure} is idempotent: {@code CREATE DATABASE IF NOT
 * EXISTS} + {@code CREATE TABLE IF NOT EXISTS} on every call.
 */
@ApiStatus.Internal
final class ClickHouseSchema {

    private ClickHouseSchema() {
    }

    private static final java.util.List<String> COORDINATE_CODEC_COLUMNS = java.util.List.of(
            "location_x", "location_y", "location_z",
            "teleport_from_x", "teleport_from_y", "teleport_from_z",
            "teleport_to_x", "teleport_to_y", "teleport_to_z",
            "source_command_block_x", "source_command_block_y", "source_command_block_z");

    static void ensure(Client client, String database, String eventsTable) {
        // The client's session database is the configured one, which on a
        // first install does not exist yet — and the server rejects any
        // request whose session database is missing, including the very
        // CREATE DATABASE that would create it. Run that one statement
        // with the session pointed at `system` (always present); the
        // session database only scopes unqualified table names, which
        // the statement has none of.
        CommandSettings bootstrap = new CommandSettings();
        bootstrap.setDatabase("system");
        execute(client, "CREATE DATABASE IF NOT EXISTS " + quoteIdentifier(database), bootstrap);
        execute(client, buildEventRecordsTable(database, eventsTable));
        // Idempotent ADD COLUMN for tables created by older Spyglass
        // versions: CH ignores ADD COLUMN IF NOT EXISTS when the column
        // is already there, but a fresh CREATE TABLE above would have
        // included it from the start.
        execute(client, "ALTER TABLE " + qualifiedTable(database, eventsTable)
                + " ADD COLUMN IF NOT EXISTS server LowCardinality(String) DEFAULT ''");
        // rollback-op records (#22): the operation reference blob the
        // per-block rolled-* search entries are synthesized from.
        execute(client, "ALTER TABLE " + qualifiedTable(database, eventsTable)
                + " ADD COLUMN IF NOT EXISTS op_reference Nullable(String) CODEC(ZSTD(1))");
        // Storage codecs (#21), idempotent for tables created before
        // them: ids are time-ordered v7 now, so a shared-prefix-aware
        // codec halves the column that used to be incompressible v4
        // noise — measured the single largest consumer of store disk,
        // twice over (the by_player projection duplicates every
        // column). Coordinates are spatially clustered, so storing
        // row-to-row differences compresses ~4x. MODIFY COLUMN with an
        // unchanged codec is a cheap metadata no-op; with a new codec
        // it applies to new parts immediately and old parts converge
        // as merges churn them.
        for (String coordinate : COORDINATE_CODEC_COLUMNS) {
            String type = coordinate.startsWith("location_") ? "Int32" : "Nullable(Int32)";
            execute(client, "ALTER TABLE " + qualifiedTable(database, eventsTable)
                    + " MODIFY COLUMN " + coordinate + " " + type + " CODEC(Delta, ZSTD(1))");
        }
        execute(client, "ALTER TABLE " + qualifiedTable(database, eventsTable)
                + " MODIFY COLUMN id UUID CODEC(ZSTD(1))");
        // One-shot migration: pre-chunked undo_history shape lacked
        // operation_id / chunk_index / chunk_count. ORDER BY changed,
        // so ALTER can't reshape the key — drop and recreate. Worst
        // case is losing the in-flight 24 h undo window, which is
        // acceptable for a schema migration.
        if (hasLegacyUndoHistory(client, database)) {
            execute(client, "DROP TABLE IF EXISTS " + undoHistoryTable(database));
        }
        execute(client, buildUndoHistoryTable(database));
        execute(client, buildToolStatesTable(database));
    }

    /**
     * {@code true} when an {@code undo_history} table exists in {@code
     * database} that predates the chunked-row layout (no {@code
     * operation_id} column). Used by {@link #ensure} to trigger the
     * one-shot drop+recreate migration.
     */
    private static boolean hasLegacyUndoHistory(Client client, String database) {
        // system.columns is queryable without privileged grants on a
        // self-managed instance; a fresh install returns 0 in `total`
        // because the table doesn't exist yet, which we treat as "not
        // legacy" so the subsequent CREATE just makes the new shape.
        String sql = "SELECT "
                + "  countIf(name = 'operation_id') AS has_op, "
                + "  count() AS total "
                + "FROM system.columns "
                + "WHERE database = '" + database.replace("'", "\\'") + "' "
                + "  AND table = 'undo_history'";
        try {
            var rows = client.queryAll(sql);
            if (rows.isEmpty()) {
                return false;
            }
            var row = rows.get(0);
            long total = row.getLong("total");
            long hasOp = row.getLong("has_op");
            // Table exists (total > 0) AND missing the new column → legacy.
            return total > 0 && hasOp == 0;
        } catch (Exception ex) {
            throw new RuntimeException("Undo schema probe failed: " + ex.getMessage(), ex);
        }
    }

    private static void execute(Client client, String sql) {
        execute(client, sql, null);
    }

    private static void execute(Client client, String sql, CommandSettings settings) {
        try (CommandResponse ignored = (settings == null
                ? client.execute(sql)
                : client.execute(sql, settings)).get(60, TimeUnit.SECONDS)) {
            // ack received
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Schema bootstrap interrupted", ie);
        } catch (Exception ex) {
            throw new RuntimeException("Schema bootstrap failed: " + sql, ex);
        }
    }

    static String qualifiedTable(String database, String table) {
        return quoteIdentifier(database) + "." + quoteIdentifier(table);
    }

    static String undoHistoryTable(String database) {
        return qualifiedTable(database, "undo_history");
    }

    static String toolStatesTable(String database) {
        return qualifiedTable(database, "tool_states");
    }

    // ---------------------------------------------------------------
    // event_records
    // ---------------------------------------------------------------

    private static String buildEventRecordsTable(String database, String table) {
        return "CREATE TABLE IF NOT EXISTS " + qualifiedTable(database, table) + " (\n"
                // --- Common (every record) ---
                + "    id UUID CODEC(ZSTD(1)),\n"
                + "    event LowCardinality(String),\n"
                + "    occurred DateTime64(3, 'UTC'),\n"
                + "    expires_at DateTime64(3, 'UTC'),\n"
                + "    origin_kind LowCardinality(String),\n"
                + "    origin_detail Nullable(String),\n"
                + "    source_kind LowCardinality(String),\n"
                + "    source_player_id Nullable(UUID),\n"
                + "    source_player_name Nullable(String),\n"
                + "    source_entity_id Nullable(UUID),\n"
                + "    source_entity_type Nullable(String),\n"
                + "    source_plugin_name Nullable(String),\n"
                + "    source_command_block_world_id Nullable(UUID),\n"
                + "    source_command_block_world_name Nullable(String),\n"
                + "    source_command_block_x Nullable(Int32) CODEC(Delta, ZSTD(1)),\n"
                + "    source_command_block_y Nullable(Int32) CODEC(Delta, ZSTD(1)),\n"
                + "    source_command_block_z Nullable(Int32) CODEC(Delta, ZSTD(1)),\n"
                + "    source_description Nullable(String),\n"
                + "    location_world_id UUID,\n"
                + "    location_world_name LowCardinality(String),\n"
                + "    location_x Int32 CODEC(Delta, ZSTD(1)),\n"
                + "    location_y Int32 CODEC(Delta, ZSTD(1)),\n"
                + "    location_z Int32 CODEC(Delta, ZSTD(1)),\n"
                + "    server LowCardinality(String) DEFAULT '',\n"
                + "    target Nullable(String),\n"
                // --- Block events (Break / Place / Use) ---
                // Materials and blockdata strings are heavily repetitive
                // across rollback windows (a //replace stone air run is
                // 100K rows of the same two materials and blockdatas).
                // LowCardinality auto-dictionaries them so the actual
                // disk + network footprint is one int per row instead of
                // a 200-500-byte BSON BlockSnapshot blob. The "extras"
                // column captures the rare per-block tile-entity state
                // (container items, sign text, banner patterns, jukebox
                // record, decorated-pot sherds) as a compact BSON blob,
                // populated only when at least one of those fields is
                // non-empty — NULL for the 99%+ of block events that are
                // plain stone/dirt/air. This is the column shape behind
                // the schema rewrite that closed the gap with
                // coreprotect-clickhouse.
                + "    before_material LowCardinality(Nullable(String)),\n"
                + "    before_blockdata LowCardinality(Nullable(String)),\n"
                + "    before_extras Nullable(String) CODEC(ZSTD(1)),\n"
                + "    after_material LowCardinality(Nullable(String)),\n"
                + "    after_blockdata LowCardinality(Nullable(String)),\n"
                + "    after_extras Nullable(String) CODEC(ZSTD(1)),\n"
                // --- Container events ---
                + "    container_type LowCardinality(Nullable(String)),\n"
                + "    slot Nullable(Int32),\n"
                + "    amount Nullable(Int32),\n"
                + "    before_item Nullable(String) CODEC(ZSTD(1)),\n"
                + "    after_item Nullable(String) CODEC(ZSTD(1)),\n"
                // --- Item events (Drop / Pickup) ---
                + "    item Nullable(String) CODEC(ZSTD(1)),\n"
                // --- Chat / Command ---
                + "    message Nullable(String),\n"
                + "    recipients Array(UUID) CODEC(ZSTD(1)),\n"
                + "    command_line Nullable(String),\n"
                // --- Join ---
                + "    address Nullable(String),\n"
                // --- Teleport ---
                + "    teleport_from_world_id Nullable(UUID),\n"
                + "    teleport_from_world_name Nullable(String),\n"
                + "    teleport_from_x Nullable(Int32) CODEC(Delta, ZSTD(1)),\n"
                + "    teleport_from_y Nullable(Int32) CODEC(Delta, ZSTD(1)),\n"
                + "    teleport_from_z Nullable(Int32) CODEC(Delta, ZSTD(1)),\n"
                + "    teleport_to_world_id Nullable(UUID),\n"
                + "    teleport_to_world_name Nullable(String),\n"
                + "    teleport_to_x Nullable(Int32) CODEC(Delta, ZSTD(1)),\n"
                + "    teleport_to_y Nullable(Int32) CODEC(Delta, ZSTD(1)),\n"
                + "    teleport_to_z Nullable(Int32) CODEC(Delta, ZSTD(1)),\n"
                + "    teleport_cause LowCardinality(Nullable(String)),\n"
                // --- Entity events (Death / Hit / Mount / Name) ---
                + "    entity_type LowCardinality(Nullable(String)),\n"
                + "    entity_id Nullable(UUID),\n"
                + "    entity_killer_type LowCardinality(Nullable(String)),\n"
                + "    entity_damage_cause LowCardinality(Nullable(String)),\n"
                + "    entity_nbt Nullable(String) CODEC(ZSTD(1)),\n"
                + "    entity_damage Nullable(Float64),\n"
                + "    entity_projectile Nullable(UInt8),\n"
                + "    entity_projectile_type LowCardinality(Nullable(String)),\n"
                + "    entity_dismount Nullable(UInt8),\n"
                + "    entity_old_name Nullable(String),\n"
                + "    entity_new_name Nullable(String),\n"
                + "    op_reference Nullable(String) CODEC(ZSTD(1)),\n"
                // --- Skip indexes ---
                + "    INDEX idx_loc_xz (location_x, location_z) TYPE minmax GRANULARITY 4,\n"
                + "    INDEX idx_world location_world_id TYPE bloom_filter GRANULARITY 4,\n"
                // --- Projection: by_player gives player-scoped lookups
                //     a primary-key seek instead of a near-full scan.
                //     id is in the projection sort key so the projection
                //     dedups in lockstep with the parent table. ---
                + "    PROJECTION by_player (\n"
                + "        SELECT *\n"
                + "        ORDER BY (source_player_id, occurred, id)\n"
                + "    )\n"
                // ReplacingMergeTree (no version column) keeps the most-
                // recently-inserted row for each unique sort-key tuple.
                // Sort key includes id so two replays of the same record
                // (same UUID) collapse to one row on next merge; distinct
                // events sharing (event, occurred) still coexist because
                // their ids differ.
                + ") ENGINE = ReplacingMergeTree\n"
                + "PARTITION BY toYYYYMM(occurred)\n"
                + "ORDER BY (event, occurred, id)\n"
                + "TTL toDateTime(expires_at)\n"
                // deduplicate_merge_projection_mode = 'rebuild' lets the
                // by_player projection coexist with ReplacingMergeTree on
                // CH 24.8+. Without it the server refuses the projection
                // (SUPPORT_IS_DISABLED, code 344) since dedup-during-merge
                // and projection rebuild interact non-trivially. 'rebuild'
                // re-projects the deduplicated parent on every part merge.
                + "SETTINGS index_granularity = 8192,"
                + " deduplicate_merge_projection_mode = 'rebuild'";
    }

    // ---------------------------------------------------------------
    // undo_history
    // ---------------------------------------------------------------

    private static String buildUndoHistoryTable(String database) {
        // Chunked-row layout: a single rollback's inverse-effects list
        // is split into N rows sharing one {@code operation_id}, with
        // {@code chunk_index} 0..N-1 and {@code chunk_count = N}. The
        // pre-chunk shape (one row per rollback, multi-GB
        // inverse_effects column at 10M scale) blew the CH parser's
        // 1 GiB chunk-allocation limit during INSERT VALUES, even
        // before MergeTree write. Chunked rows keep every individual
        // INSERT value bounded (~20 MB BSON / ~27 MB base64 at the
        // 100k-effects-per-chunk default) so the parser path never
        // sees a multi-GB literal.
        //
        // Primary key includes operation_id and chunk_index so each
        // chunk row is independently key'd. ReplacingMergeTree(deleted)
        // still works for tombstones — a deleted=1 row inserted with
        // the same key collapses on next merge; pop() uses FINAL on
        // read for immediate visibility.
        return "CREATE TABLE IF NOT EXISTS " + undoHistoryTable(database) + " (\n"
                + "    operation_id UUID,\n"
                + "    chunk_index UInt32,\n"
                + "    chunk_count UInt32,\n"
                + "    player_id UUID,\n"
                + "    created_at DateTime64(3, 'UTC'),\n"
                + "    operation_type LowCardinality(String),\n"
                + "    inverse_effects String CODEC(ZSTD(1)),\n"
                + "    deleted UInt8 DEFAULT 0\n"
                + ") ENGINE = ReplacingMergeTree(deleted)\n"
                + "PARTITION BY toYYYYMM(created_at)\n"
                + "ORDER BY (player_id, created_at, operation_id, chunk_index)\n"
                + "TTL toDateTime(created_at) + INTERVAL 1 DAY\n"
                + "SETTINGS index_granularity = 8192";
    }

    // ---------------------------------------------------------------
    // tool_states
    // ---------------------------------------------------------------

    private static String buildToolStatesTable(String database) {
        // EmbeddedRocksDB: the CH table is fronted by an embedded
        // RocksDB instance. Point lookups by primary key are O(log N)
        // with no granule scan. Perfect for a small mutable
        // player_id -> enabled_at registry.
        return "CREATE TABLE IF NOT EXISTS " + toolStatesTable(database) + " (\n"
                + "    player_id UUID,\n"
                + "    enabled_at DateTime64(3, 'UTC')\n"
                + ") ENGINE = EmbeddedRocksDB\n"
                + "PRIMARY KEY player_id";
    }

    private static String quoteIdentifier(String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
    }
}
