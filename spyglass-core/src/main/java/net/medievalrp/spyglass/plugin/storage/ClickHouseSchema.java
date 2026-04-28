package net.medievalrp.spyglass.plugin.storage;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.command.CommandResponse;
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

    static void ensure(Client client, String database, String eventsTable) {
        execute(client, "CREATE DATABASE IF NOT EXISTS " + quoteIdentifier(database));
        execute(client, buildEventRecordsTable(database, eventsTable));
        // Idempotent ADD COLUMN for tables created by older Spyglass
        // versions: CH ignores ADD COLUMN IF NOT EXISTS when the column
        // is already there, but a fresh CREATE TABLE above would have
        // included it from the start.
        execute(client, "ALTER TABLE " + qualifiedTable(database, eventsTable)
                + " ADD COLUMN IF NOT EXISTS server LowCardinality(String) DEFAULT ''");
        execute(client, buildUndoHistoryTable(database));
        execute(client, buildToolStatesTable(database));
    }

    private static void execute(Client client, String sql) {
        try (CommandResponse ignored = client.execute(sql).get(60, TimeUnit.SECONDS)) {
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
                + "    id UUID,\n"
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
                + "    source_command_block_x Nullable(Int32),\n"
                + "    source_command_block_y Nullable(Int32),\n"
                + "    source_command_block_z Nullable(Int32),\n"
                + "    source_description Nullable(String),\n"
                + "    location_world_id UUID,\n"
                + "    location_world_name LowCardinality(String),\n"
                + "    location_x Int32,\n"
                + "    location_y Int32,\n"
                + "    location_z Int32,\n"
                + "    server LowCardinality(String) DEFAULT '',\n"
                + "    target Nullable(String),\n"
                // --- Block events (Break / Place / Use) ---
                + "    original_block Nullable(String) CODEC(ZSTD(1)),\n"
                + "    new_block Nullable(String) CODEC(ZSTD(1)),\n"
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
                + "    teleport_from_x Nullable(Int32),\n"
                + "    teleport_from_y Nullable(Int32),\n"
                + "    teleport_from_z Nullable(Int32),\n"
                + "    teleport_to_world_id Nullable(UUID),\n"
                + "    teleport_to_world_name Nullable(String),\n"
                + "    teleport_to_x Nullable(Int32),\n"
                + "    teleport_to_y Nullable(Int32),\n"
                + "    teleport_to_z Nullable(Int32),\n"
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
        return "CREATE TABLE IF NOT EXISTS " + undoHistoryTable(database) + " (\n"
                + "    id UUID,\n"
                + "    player_id UUID,\n"
                + "    created_at DateTime64(3, 'UTC'),\n"
                + "    operation_type LowCardinality(String),\n"
                + "    inverse_effects String CODEC(ZSTD(1)),\n"
                + "    deleted UInt8 DEFAULT 0\n"
                + ") ENGINE = ReplacingMergeTree(deleted)\n"
                + "PARTITION BY toYYYYMM(created_at)\n"
                + "ORDER BY (player_id, created_at, id)\n"
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
