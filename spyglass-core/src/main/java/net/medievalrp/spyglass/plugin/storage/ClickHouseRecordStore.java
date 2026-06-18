package net.medievalrp.spyglass.plugin.storage;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.command.CommandResponse;
import com.clickhouse.client.api.data_formats.RowBinaryFormatWriter;
import com.clickhouse.client.api.insert.InsertResponse;
import com.clickhouse.client.api.insert.InsertSettings;
import com.clickhouse.client.api.metadata.TableSchema;
import com.clickhouse.client.api.query.GenericRecord;
import com.clickhouse.client.api.query.Records;
import com.clickhouse.data.ClickHouseFormat;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.medievalrp.spyglass.api.event.BlockBreakRecord;
import net.medievalrp.spyglass.api.event.BlockPlaceRecord;
import net.medievalrp.spyglass.api.event.BlockSnapshot;
import net.medievalrp.spyglass.api.event.BlockUseRecord;
import net.medievalrp.spyglass.api.event.ChatRecord;
import net.medievalrp.spyglass.api.event.CommandRecord;
import net.medievalrp.spyglass.api.event.ContainerDepositRecord;
import net.medievalrp.spyglass.api.event.ContainerInteractRecord;
import net.medievalrp.spyglass.api.event.ContainerWithdrawRecord;
import net.medievalrp.spyglass.api.event.EntityDeathRecord;
import net.medievalrp.spyglass.api.event.EntityHitRecord;
import net.medievalrp.spyglass.api.event.EntityMountRecord;
import net.medievalrp.spyglass.api.event.EntityNameRecord;
import net.medievalrp.spyglass.api.event.EventCatalog;
import net.medievalrp.spyglass.api.event.EventRecord;
import net.medievalrp.spyglass.api.event.ItemDropRecord;
import net.medievalrp.spyglass.api.event.ItemPickupRecord;
import net.medievalrp.spyglass.api.event.JoinRecord;
import net.medievalrp.spyglass.api.event.Origin;
import net.medievalrp.spyglass.api.event.QuitRecord;
import net.medievalrp.spyglass.api.event.RollbackOpRecord;
import net.medievalrp.spyglass.api.event.Source;
import net.medievalrp.spyglass.api.event.StoredItem;
import net.medievalrp.spyglass.api.rollback.RollbackEffect;
import net.medievalrp.spyglass.api.event.TeleportRecord;
import net.medievalrp.spyglass.api.query.Flag;
import net.medievalrp.spyglass.api.query.QueryPredicate;
import net.medievalrp.spyglass.api.query.QueryRequest;
import net.medievalrp.spyglass.api.query.QueryResult;
import net.medievalrp.spyglass.api.query.Sort;
import net.medievalrp.spyglass.api.util.BlockLocation;
import org.jetbrains.annotations.ApiStatus;

/**
 * Wide flat-table {@link RecordStore} backed by ClickHouse, fully on
 * the {@code clickhouse-java client-v2} stack.
 *
 * <h2>Why client-v2 only (no JDBC)</h2>
 *
 * <p>Direct measurement showed JDBC roundtrip latency dwarfing
 * server-side query time on Mac Docker — {@code curl} hits the same
 * SELECT in 1-2 ms while clickhouse-jdbc 0.9.8 returns in 40-50 ms
 * for the same query. Client-v2's {@link Client#queryAll} returns
 * {@link GenericRecord}s decoded from the binary
 * {@code RowBinaryWithNamesAndTypes} format with HTTP keep-alive
 * pooling and no JDBC overhead. Inserts, schema bootstrap, and
 * queries all share one {@link Client} instance.
 *
 * <h2>Encoding</h2>
 *
 * Inserts go through {@link RowBinaryFormatWriter} — every row sets
 * every column (Nullable columns get {@code null} explicitly), and a
 * single {@link Client#insert} call ships the buffered RowBinary
 * stream over HTTP keep-alive. Reads come back as
 * {@link GenericRecord}s with typed getters; {@link #decodeRow}
 * switches on the {@code event} discriminator to instantiate the
 * right concrete {@link EventRecord} subtype.
 *
 * <p>Heavy nested fields ({@link BlockSnapshot}, {@link StoredItem})
 * are stored as base64-encoded BSON in dedicated ZSTD-compressed
 * String columns via {@link BsonBlobs}; the codec hierarchy already
 * knows how to encode/decode them.
 */
@ApiStatus.Internal
public final class ClickHouseRecordStore implements RecordStore {

    private static final List<String> COMMON_COLUMNS = List.of(
            "id", "event", "occurred", "expires_at",
            "origin_kind", "origin_detail",
            "source_kind", "source_player_id", "source_player_name",
            "source_entity_id", "source_entity_type", "source_plugin_name",
            "source_command_block_world_id", "source_command_block_world_name",
            "source_command_block_x", "source_command_block_y", "source_command_block_z",
            "source_description",
            "location_world_id", "location_world_name",
            "location_x", "location_y", "location_z",
            "server", "target");

    private static final List<String> SUMMARY_EXTRAS = List.of(
            "container_type", "slot", "amount",
            "message", "recipients", "command_line",
            "address",
            "teleport_from_world_id", "teleport_from_world_name",
            "teleport_from_x", "teleport_from_y", "teleport_from_z",
            "teleport_to_world_id", "teleport_to_world_name",
            "teleport_to_x", "teleport_to_y", "teleport_to_z",
            "teleport_cause",
            "entity_type", "entity_id",
            "entity_killer_type", "entity_damage_cause",
            "entity_damage", "entity_projectile", "entity_projectile_type",
            "entity_dismount", "entity_old_name", "entity_new_name",
            "extensions");

    private static final List<String> HEAVY_COLUMNS = List.of(
            "before_material", "before_blockdata", "before_extras",
            "after_material", "after_blockdata", "after_extras",
            "before_item", "after_item",
            "item",
            "entity_nbt",
            "op_reference");

    /**
     * Trimmed column list for the rollback streaming path. Includes
     * every column that {@link #decodeRow} touches for Rollbackable
     * record types (Block/Container/EntityDeath) plus all source/
     * origin/location columns that {@link #readSource} and
     * {@link #readOrigin} call across all event types — those readers
     * run on every row regardless of whether the row's event is
     * rollbackable.
     *
     * <p>Drops only columns that no Rollbackable event needs: teleport
     * coords, chat message/recipients/command_line, join address,
     * entity damage/projectile/dismount/name. For a 100K break-event
     * rollback this cuts the network response from ~360 MB to ~150 MB
     * and the CH query time from ~1.2 s to ~600 ms (the heavy hitters
     * are the ZSTD original_block/new_block blobs which we still need).
     */
    /**
     * Hard SQL filter for the rollback streaming path: only events
     * that map to a Rollbackable record type. Mirrors
     * {@code EventCatalog} — events that map to BlockBreak/Place,
     * ContainerDeposit/Withdraw, or EntityDeath. Anything else
     * (chat, teleport, join, item drop, etc.) is filtered at the
     * CH level so the query never reads or ships those rows.
     */
    private static final String ROLLBACKABLE_EVENT_FILTER =
            "event IN ("
                    + "'break','place','decay','form','grow','ignite','brush','vault',"
                    + "'deposit','withdraw',"
                    + "'entity-deposit','entity-withdraw',"
                    + "'bookshelf-insert','bookshelf-remove',"
                    + "'pot-insert','pot-remove',"
                    + "'shulker-deposit','shulker-withdraw',"
                    + "'bundle-insert','bundle-extract',"
                    + "'crafter','death')";

    private static final List<String> ROLLBACK_COLUMNS = List.of(
            "id", "event", "occurred", "expires_at",
            // origin
            "origin_kind", "origin_detail",
            // source — readSource() needs all of these for every row
            "source_kind", "source_player_id", "source_player_name",
            "source_entity_id", "source_entity_type", "source_plugin_name",
            "source_command_block_world_id", "source_command_block_world_name",
            "source_command_block_x", "source_command_block_y", "source_command_block_z",
            "source_description",
            // location
            "location_world_id", "location_world_name",
            "location_x", "location_y", "location_z",
            "server", "target",
            // block events — structured columns + sparse extras blob
            "before_material", "before_blockdata", "before_extras",
            "after_material", "after_blockdata", "after_extras",
            // container events
            "container_type", "slot", "amount",
            "before_item", "after_item",
            // entity death (the only Rollbackable entity event)
            "entity_type", "entity_id", "entity_nbt",
            "entity_killer_type", "entity_damage_cause");

    // The strictly-needed columns to build a RollbackEffect (#67). Drops
    // everything streamRollbackEffects never reads vs ROLLBACK_COLUMNS:
    // expires_at, origin_*, all source_*, server, target, plus the
    // container_type/amount and entity killer/damage columns that the
    // effect constructors ignore. Cutting the SELECT this far trims both
    // the wire response and the per-row decode — there is no Origin,
    // Source, or record wrapper to allocate, only the effect's own fields.
    private static final List<String> LEAN_ROLLBACK_COLUMNS = List.of(
            "id", "event", "occurred",
            "location_world_id", "location_world_name",
            "location_x", "location_y", "location_z",
            "before_material", "before_blockdata", "before_extras",
            "after_material", "after_blockdata", "after_extras",
            "slot", "before_item", "after_item",
            "entity_type", "entity_id", "entity_nbt");

    private final PredicateToSql predicateToSql = new PredicateToSql();
    private final Client client;
    private final TableSchema tableSchema;
    private final String database;
    private final String table;
    private final String qualifiedTable;
    private final String summarySelect;
    private final String fullSelect;
    private final String rollbackSelect;
    private final String leanRollbackSelect;

    public ClickHouseRecordStore(String host, int port, String database, String table,
                                 String user, String password, boolean ssl) {
        this.database = database;
        this.table = table;
        this.qualifiedTable = ClickHouseSchema.qualifiedTable(database, table);
        this.summarySelect = String.join(", ", concat(COMMON_COLUMNS, SUMMARY_EXTRAS));
        this.fullSelect = String.join(", ", concat(COMMON_COLUMNS, SUMMARY_EXTRAS, HEAVY_COLUMNS));
        this.rollbackSelect = String.join(", ", ROLLBACK_COLUMNS);
        this.leanRollbackSelect = String.join(", ", LEAN_ROLLBACK_COLUMNS);

        this.client = new Client.Builder()
                .addEndpoint((ssl ? "https" : "http") + "://" + host + ":" + port)
                .setUsername(user)
                .setPassword(password == null ? "" : password)
                .setDefaultDatabase(database)
                // LZ4 on insert traffic only — request bodies are
                // big and compress well, but small query responses
                // pay decompression-setup cost without much win.
                .compressClientRequest(true)
                .compressServerResponse(false)
                .build();
        ClickHouseSchema.ensure(client, database, table);
        this.tableSchema = client.getTableSchema(table, database);
    }

    public Client client() {
        return client;
    }

    public String databaseName() {
        return database;
    }

    public String qualifiedTableName() {
        return qualifiedTable;
    }

    // ===== Save =================================================

    @Override
    public void save(List<EventRecord> records) {
        if (records.isEmpty()) {
            return;
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(records.size() * 256);
        try {
            RowBinaryFormatWriter writer = new RowBinaryFormatWriter(
                    buffer, tableSchema, ClickHouseFormat.RowBinary);
            for (EventRecord record : records) {
                writer.clearRow();
                writeRow(writer, record);
                writer.commitRow();
            }
        } catch (IOException ex) {
            throw new RuntimeException("ClickHouse RowBinary write failed: " + ex.getMessage(), ex);
        }

        // async_insert=1 + wait_for_async_insert=0 → fire-and-forget. CH
        // server-side buffers our rows and flushes them in big efficient
        // batches; we don't sit blocked on every INSERT round-trip. The
        // 1-second window where data lives only in CH's buffer is covered
        // by Spyglass's own WAL (which fsyncs each batch BEFORE the CH
        // push), so a CH crash mid-buffer leaves a recoverable file on
        // disk. Trade-off: insert latency ~3-5x faster during bursts;
        // small risk of dup-on-retry if CH partially flushed before
        // failing — acceptable for an audit log.
        InsertSettings settings = new InsertSettings()
                .setDatabase(database)
                .serverSetting("async_insert", "1")
                .serverSetting("wait_for_async_insert", "0");
        try (InsertResponse ignored = client.insert(
                table,
                new ByteArrayInputStream(buffer.toByteArray()),
                ClickHouseFormat.RowBinary,
                settings).get(60, TimeUnit.SECONDS)) {
            // ack received
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("ClickHouse insert interrupted", ie);
        } catch (ExecutionException | TimeoutException ex) {
            throw new RuntimeException("ClickHouse insert failed: " + ex.getMessage(), ex);
        }
    }

    private void writeRow(RowBinaryFormatWriter writer, EventRecord record) throws IOException {
        // Every column must be set per row. Nullable columns get an
        // explicit null — skipping a column in RowBinary doesn't emit
        // a NULL marker, it emits zero bytes, which corrupts every
        // following column.
        writeCommon(writer, record);
        writeBlockColumns(writer, record);
        writeContainerColumns(writer, record);
        writeItemColumns(writer, record);
        writeChatCommandColumns(writer, record);
        writeJoinColumns(writer, record);
        writeTeleportColumns(writer, record);
        writeEntityColumns(writer, record);
        writeOpColumns(writer, record);
    }

    private void writeOpColumns(RowBinaryFormatWriter writer, EventRecord record) throws IOException {
        writer.setValue("op_reference",
                record instanceof RollbackOpRecord op ? op.reference() : null);
    }

    private void writeCommon(RowBinaryFormatWriter writer, EventRecord record) throws IOException {
        Origin origin = record.origin();
        Source source = record.source();
        BlockLocation location = record.location();

        writer.setValue("id", net.medievalrp.spyglass.api.util.EventIds.sequenceOf(record.id()));
        writer.setString("event", record.event());
        writer.setDateTime("occurred", toLocalDateTime(record.occurred()));
        writer.setDateTime("expires_at", toLocalDateTime(record.expiresAt()));
        writer.setString("origin_kind", nullToEmpty(origin == null ? null : origin.kind()));
        writer.setValue("origin_detail", origin == null ? null : origin.detail());
        writer.setString("source_kind", nullToEmpty(source == null ? null : source.kind()));
        writer.setValue("source_player_id", source == null ? null : source.playerId());
        writer.setValue("source_player_name", source == null ? null : source.playerName());
        writer.setValue("source_entity_id", source == null ? null : source.entityId());
        writer.setValue("source_entity_type", source == null ? null : source.entityType());
        writer.setValue("source_plugin_name", source == null ? null : source.pluginName());
        BlockLocation cmdLoc = source == null ? null : source.commandBlockLocation();
        writer.setValue("source_command_block_world_id", cmdLoc == null ? null : cmdLoc.worldId());
        writer.setValue("source_command_block_world_name", cmdLoc == null ? null : cmdLoc.worldName());
        writer.setValue("source_command_block_x", cmdLoc == null ? null : cmdLoc.x());
        writer.setValue("source_command_block_y", cmdLoc == null ? null : cmdLoc.y());
        writer.setValue("source_command_block_z", cmdLoc == null ? null : cmdLoc.z());
        writer.setValue("source_description", source == null ? null : source.description());
        writer.setValue("location_world_id", location.worldId());
        writer.setString("location_world_name", nullToEmpty(location.worldName()));
        writer.setInteger("location_x", location.x());
        writer.setInteger("location_y", location.y());
        writer.setInteger("location_z", location.z());
        writer.setString("server", nullToEmpty(record.server()));
        writer.setValue("target", record.target());
        // Extension fields ride on every row (empty map for records that don't
        // expose them — see EventRecord#extensions). RowBinary needs every
        // column set per row, so this is unconditional.
        writer.setValue("extensions", record.extensions());
    }

    private void writeBlockColumns(RowBinaryFormatWriter writer, EventRecord record) throws IOException {
        BlockSnapshot original = null;
        BlockSnapshot replacement = null;
        if (record instanceof BlockBreakRecord br) {
            original = br.originalBlock();
            replacement = br.newBlock();
        } else if (record instanceof BlockPlaceRecord pl) {
            original = pl.originalBlock();
            replacement = pl.newBlock();
        }
        // Structured columns: material + blockData go into LowCardinality
        // columns (auto-dictionary on the CH side). Tile-entity extras
        // (containerItems, signs, banners, jukebox, decorated-pot
        // sherds) only get serialized for the rare blocks that have
        // them — for plain stone/dirt/air/etc. the *_extras column is
        // NULL and costs ~1 byte per row instead of ~200-500.
        writer.setValue("before_material",  original    == null ? null : original.material().name());
        writer.setValue("before_blockdata", original    == null ? null : original.blockData());
        writer.setValue("before_extras",    BsonBlobs.encodeBlockExtras(original));
        writer.setValue("after_material",   replacement == null ? null : replacement.material().name());
        writer.setValue("after_blockdata",  replacement == null ? null : replacement.blockData());
        writer.setValue("after_extras",     BsonBlobs.encodeBlockExtras(replacement));
    }

    private void writeContainerColumns(RowBinaryFormatWriter writer, EventRecord record) throws IOException {
        String containerType = null;
        Integer slot = null;
        Integer amount = null;
        StoredItem before = null;
        StoredItem after = null;
        if (record instanceof ContainerDepositRecord d) {
            containerType = d.containerType();
            slot = d.slot();
            amount = d.amount();
            before = d.beforeItem();
            after = d.afterItem();
        } else if (record instanceof ContainerWithdrawRecord w) {
            containerType = w.containerType();
            slot = w.slot();
            amount = w.amount();
            before = w.beforeItem();
            after = w.afterItem();
        } else if (record instanceof ItemDropRecord d) {
            amount = d.amount();
        } else if (record instanceof ItemPickupRecord p) {
            amount = p.amount();
        }
        writer.setValue("container_type", containerType);
        writer.setValue("slot", slot);
        writer.setValue("amount", amount);
        writer.setValue("before_item", BsonBlobs.encodeStoredItem(before));
        writer.setValue("after_item", BsonBlobs.encodeStoredItem(after));
    }

    private void writeItemColumns(RowBinaryFormatWriter writer, EventRecord record) throws IOException {
        StoredItem item = null;
        if (record instanceof ItemDropRecord d) {
            item = d.item();
        } else if (record instanceof ItemPickupRecord p) {
            item = p.item();
        }
        writer.setValue("item", BsonBlobs.encodeStoredItem(item));
    }

    private void writeChatCommandColumns(RowBinaryFormatWriter writer, EventRecord record) throws IOException {
        String message = null;
        List<UUID> recipients = List.of();
        String commandLine = null;
        if (record instanceof ChatRecord c) {
            message = c.message();
            recipients = c.recipients();
        } else if (record instanceof CommandRecord c) {
            commandLine = c.commandLine();
        }
        writer.setValue("message", message);
        writer.setList("recipients", recipients);
        writer.setValue("command_line", commandLine);
    }

    private void writeJoinColumns(RowBinaryFormatWriter writer, EventRecord record) throws IOException {
        String address = null;
        if (record instanceof JoinRecord j) {
            address = j.address();
        }
        writer.setValue("address", address);
    }

    private void writeTeleportColumns(RowBinaryFormatWriter writer, EventRecord record) throws IOException {
        BlockLocation from = null;
        BlockLocation to = null;
        String cause = null;
        if (record instanceof TeleportRecord t) {
            from = t.from();
            to = t.to();
            cause = t.cause();
        }
        writeOptionalLocationColumns(writer, "teleport_from_", from);
        writeOptionalLocationColumns(writer, "teleport_to_", to);
        writer.setValue("teleport_cause", cause);
    }

    private void writeOptionalLocationColumns(RowBinaryFormatWriter writer, String prefix, BlockLocation location)
            throws IOException {
        writer.setValue(prefix + "world_id", location == null ? null : location.worldId());
        writer.setValue(prefix + "world_name", location == null ? null : location.worldName());
        writer.setValue(prefix + "x", location == null ? null : location.x());
        writer.setValue(prefix + "y", location == null ? null : location.y());
        writer.setValue(prefix + "z", location == null ? null : location.z());
    }

    private void writeEntityColumns(RowBinaryFormatWriter writer, EventRecord record) throws IOException {
        String entityType = null;
        UUID entityId = null;
        String killerType = null;
        String damageCause = null;
        String entityNbt = null;
        Double damage = null;
        Byte projectile = null;
        String projectileType = null;
        Byte dismount = null;
        String oldName = null;
        String newName = null;
        if (record instanceof EntityDeathRecord d) {
            entityType = d.entityType();
            entityId = d.entityId();
            killerType = d.killerType();
            damageCause = d.damageCause();
            entityNbt = d.entityNbt();
        } else if (record instanceof EntityHitRecord h) {
            entityType = h.victimType();
            entityId = h.victimId();
            damage = h.damage();
            projectile = (byte) (h.projectile() ? 1 : 0);
            projectileType = h.projectileType();
        } else if (record instanceof EntityMountRecord m) {
            entityType = m.mountType();
            entityId = m.mountId();
            dismount = (byte) (m.dismount() ? 1 : 0);
        } else if (record instanceof EntityNameRecord n) {
            entityType = n.entityType();
            entityId = n.entityId();
            oldName = n.oldName();
            newName = n.newName();
        }
        writer.setValue("entity_type", entityType);
        writer.setValue("entity_id", entityId);
        writer.setValue("entity_killer_type", killerType);
        writer.setValue("entity_damage_cause", damageCause);
        writer.setValue("entity_nbt", entityNbt);
        writer.setValue("entity_damage", damage);
        writer.setValue("entity_projectile", projectile);
        writer.setValue("entity_projectile_type", projectileType);
        writer.setValue("entity_dismount", dismount);
        writer.setValue("entity_old_name", oldName);
        writer.setValue("entity_new_name", newName);
    }

    // ===== Query ================================================

    @Override
    public QueryResult query(QueryRequest request) {
        return runQuery(request, true);
    }

    @Override
    public QueryResult querySummary(QueryRequest request) {
        return runQuery(request, false);
    }

    @Override
    public QueryPage queryPage(QueryRequest request, QueryPage.Cursor cursor, int pageSize) {
        List<EventRecord> records = new ArrayList<>(Math.min(pageSize, 4096));
        QueryPage.Cursor next = streamRollback(request, cursor, pageSize, records::add);
        return new QueryPage(records, next);
    }

    @Override
    public QueryPage.Cursor streamRollback(QueryRequest request, QueryPage.Cursor cursor,
                                           int windowLimit, RecordSink sink) {
        // Keyset-paginated streaming read. Memory is O(pageSize) per
        // call instead of O(matchSet) — required for million-row
        // rollbacks that would otherwise OOM the JVM during the single
        // queryAll() materialization. The cursor is the (occurred, id)
        // tuple of the last row we returned; we ask CH for everything
        // strictly past that point in the configured sort direction.
        // Rollback uses a trimmed column list and a hard event-type
        // filter. Two effects:
        //   1. Non-rollbackable events (chat, teleport, hit, mount,
        //      named, item drop/pickup, container interact, block use,
        //      join, quit, command, sculk) are excluded at the CH
        //      level — fewer rows to scan / sort / ship.
        //   2. The trimmed SELECT skips ~10 columns no Rollbackable
        //      record type reads (teleport coords, chat
        //      message/recipients/command_line, join address, entity
        //      damage/projectile/dismount/name). Cuts the network
        //      response ~3x on block-heavy rollbacks and the
        //      decodeRow cost in lockstep.
        String sql = buildRollbackSql(rollbackSelect, request, cursor, windowLimit);

        // Streaming reader — pulls rows from CH as they arrive instead
        // of materializing every GenericRecord up front. The OOM at
        // 1M-row pageSize was the queryAll() materialization step
        // allocating ~1 KB+ per intermediate GenericRecord (the JDBC
        // shape, not our slim EventRecord). With Records, each row is
        // decoded into an EventRecord and the GenericRecord becomes
        // immediately collectible — peak heap is bounded by the slim
        // post-decode List<EventRecord> instead of the heavy
        // intermediate set. Memory at 1M rows drops from ~3 GB to
        // ~100-200 MB.
        Instant lastOccurred = null;
        UUID lastId = null;
        int rowCount = 0;
        try (Records rows = client.queryRecords(sql).get()) {
            for (GenericRecord row : rows) {
                rowCount++;
                EventRecord record = decodeRow(row, true);
                if (record != null) {
                    sink.accept(record);
                }
                // Always advance the cursor — even if decodeRow returned
                // null (unknown event). Skipping over an undecodable row
                // is fine; freezing on it would loop forever.
                lastOccurred = row.getInstant("occurred");
                lastId = net.medievalrp.spyglass.api.util.EventIds.uuidOf(row.getLong("id"));
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("ClickHouse paginated query interrupted", ie);
        } catch (java.util.concurrent.ExecutionException ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            throw new RuntimeException("ClickHouse paginated query failed: " + cause.getMessage(), cause);
        } catch (RuntimeException ex) {
            throw new RuntimeException("ClickHouse paginated query failed: " + ex.getMessage(), ex);
        } catch (Exception ex) {
            // Records.close() declares throws Exception. In practice
            // the close path swallows recoverable errors and only
            // throws on an unrecoverable client-side IO problem.
            throw new RuntimeException("ClickHouse paginated query close failed: " + ex.getMessage(), ex);
        }
        return (rowCount == windowLimit && lastOccurred != null)
                ? new QueryPage.Cursor(lastOccurred, lastId)
                : null;
    }

    @Override
    public QueryPage.Cursor streamRollbackEffects(QueryRequest request, QueryPage.Cursor cursor,
                                                  int windowLimit, boolean rollback,
                                                  RollbackEffectSink sink) {
        // Allocation-lean rollback read (#67): same keyset-streamed scan as
        // streamRollback, but the trimmed LEAN_ROLLBACK_COLUMNS SELECT and
        // decodeEffect build only the RollbackEffect the engine applies —
        // no EventRecord, Origin, Source, server/target, or expires_at is
        // ever materialized. On a 2M-block rollback that removes the bulk
        // of the per-row object graph (the firehose that, surviving one
        // young GC under MaxTenuringThreshold=1, promoted to old gen and
        // forced the Mixed-GC freeze).
        String sql = buildRollbackSql(leanRollbackSelect, request, cursor, windowLimit);
        Instant lastOccurred = null;
        UUID lastId = null;
        int rowCount = 0;
        try (Records rows = client.queryRecords(sql).get()) {
            for (GenericRecord row : rows) {
                rowCount++;
                // occurred/id are the cursor coordinates; read per row so a
                // window that drains mid-page checkpoints at the right
                // keyset position. Both are transient unless retained as
                // the window's last.
                Instant occurred = row.getInstant("occurred");
                UUID id = net.medievalrp.spyglass.api.util.EventIds.uuidOf(row.getLong("id"));
                emitEffect(row, rollback, occurred, id, sink);
                lastOccurred = occurred;
                lastId = id;
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("ClickHouse paginated query interrupted", ie);
        } catch (java.util.concurrent.ExecutionException ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            throw new RuntimeException("ClickHouse paginated query failed: " + cause.getMessage(), cause);
        } catch (RuntimeException ex) {
            throw new RuntimeException("ClickHouse paginated query failed: " + ex.getMessage(), ex);
        } catch (Exception ex) {
            throw new RuntimeException("ClickHouse paginated query close failed: " + ex.getMessage(), ex);
        }
        return (rowCount == windowLimit && lastOccurred != null)
                ? new QueryPage.Cursor(lastOccurred, lastId)
                : null;
    }

    // Shared rollback SQL: rollbackable-event filter, keyset cursor in the
    // configured sort direction, and the spill-to-disk SETTINGS that keep a
    // large LIMIT under CH's default 1 GiB per-query budget. The only
    // difference between the record and effect readers is the column list.
    private String buildRollbackSql(String select, QueryRequest request,
                                    QueryPage.Cursor cursor, int windowLimit) {
        StringBuilder sql = new StringBuilder("SELECT ")
                .append(select)
                .append(" FROM ").append(qualifiedTable);

        List<QueryPredicate> predicates = new ArrayList<>(request.predicates());
        if (request.flags().contains(Flag.NO_CHAT)) {
            predicates.add(new QueryPredicate.Exists(RecordFields.MESSAGE, false));
        }
        String where = predicateToSql.translate(predicates);
        boolean newestFirst = request.sort() != Sort.OLDEST_FIRST;
        String op = newestFirst ? "<" : ">";
        if (!where.isEmpty()) {
            sql.append(" WHERE ").append(where);
            sql.append(" AND ").append(ROLLBACKABLE_EVENT_FILTER);
        } else {
            sql.append(" WHERE ").append(ROLLBACKABLE_EVENT_FILTER);
        }
        if (cursor != null) {
            sql.append(" AND ");
            // v2 ids are UInt64 sequences; the cursor UUID maps back
            // through EventIds at this boundary.
            sql.append("(occurred, id) ").append(op).append(" (")
                    .append(chTimestamp(cursor.occurred())).append(", ")
                    .append(Long.toUnsignedString(
                            net.medievalrp.spyglass.api.util.EventIds.sequenceOf(cursor.id())))
                    .append(")");
        }
        sql.append(" ORDER BY occurred ").append(newestFirst ? "DESC" : "ASC")
                .append(", id ").append(newestFirst ? "DESC" : "ASC")
                .append(" LIMIT ").append(windowLimit);
        // CH defaults max_memory_usage to 1 GiB on Pelican-managed
        // installs. A 10M-row LIMIT with sort blows past that during
        // MergeSortingTransform / ParallelFormattingOutputFormat. Raise
        // the per-query budget AND enable spill-to-disk so even an
        // operator-asked-for 10M rollback completes without an OOM
        // exception bouncing through the retry loop. The numbers here
        // are per-query soft caps; the CH server's hard limit
        // (max_server_memory_usage) still applies.
        sql.append(" SETTINGS")
                .append(" max_memory_usage = 8000000000")
                .append(", max_memory_usage_for_user = 0")
                .append(", max_bytes_before_external_sort = 1073741824")
                .append(", max_bytes_before_external_group_by = 1073741824");
        return sql.toString();
    }

    // Emit one row to the sink in the requested direction (rollback reverts;
    // restore re-applies / undoes an undo). A simple block-replace goes out
    // as primitives via sink.block() — no effect or snapshot object built;
    // everything else builds the effect and uses sink.complex(). Mirrors the
    // per-record-type Rollbackable.rollbackEffect()/restoreEffect() logic
    // exactly — keep the two in lockstep.
    private void emitEffect(GenericRecord row, boolean rollback,
                            Instant occurred, UUID id, RollbackEffectSink sink) {
        String event = row.getString("event");
        Class<? extends EventRecord> clazz = EventCatalog.recordClassOf(event);
        if (clazz == null) {
            sink.skip(occurred, id);
            return;
        }
        if (clazz == BlockBreakRecord.class || clazz == BlockPlaceRecord.class) {
            // Resolve which stored state we write (replacement) vs. expect
            // currently present (expectedCurrent). rollback writes the
            // before-state expecting the after-state; restore is the inverse.
            String replMaterial;
            String replData;
            String replExtras;
            String expData;
            if (rollback) {
                replMaterial = row.getString("before_material");
                replData = row.getString("before_blockdata");
                replExtras = row.getString("before_extras");
                expData = row.getString("after_blockdata");
            } else {
                replMaterial = row.getString("after_material");
                replData = row.getString("after_blockdata");
                replExtras = row.getString("after_extras");
                expData = row.getString("before_blockdata");
            }
            boolean simple = replData != null && (replExtras == null || replExtras.isEmpty());
            if (simple) {
                sink.block(row.getUUID("location_world_id"),
                        row.getInteger("location_x"),
                        row.getInteger("location_y"),
                        row.getInteger("location_z"),
                        replData, expData, occurred, id);
                return;
            }
            // Tile-entity payload (or malformed block-data): object path.
            BlockLocation location = readLocation(row);
            BlockSnapshot replacement = BsonBlobs.decodeBlockSnapshotFromColumns(
                    replMaterial, replData, replExtras);
            BlockSnapshot expected = rollback
                    ? BsonBlobs.decodeBlockSnapshotFromColumns(
                            row.getString("after_material"), row.getString("after_blockdata"),
                            row.getString("after_extras"))
                    : BsonBlobs.decodeBlockSnapshotFromColumns(
                            row.getString("before_material"), row.getString("before_blockdata"),
                            row.getString("before_extras"));
            sink.complex(new RollbackEffect.BlockReplace(location, expected, replacement), occurred, id);
            return;
        }
        if (clazz == ContainerDepositRecord.class || clazz == ContainerWithdrawRecord.class) {
            BlockLocation location = readLocation(row);
            int slot = row.getInteger("slot");
            StoredItem before = BsonBlobs.decodeStoredItem(row.getString("before_item"));
            StoredItem after = BsonBlobs.decodeStoredItem(row.getString("after_item"));
            sink.complex(rollback
                    ? new RollbackEffect.ContainerSlotWrite(location, slot, after, before)
                    : new RollbackEffect.ContainerSlotWrite(location, slot, before, after), occurred, id);
            return;
        }
        if (clazz == EntityDeathRecord.class) {
            BlockLocation location = readLocation(row);
            String entityType = row.getString("entity_type");
            if (rollback) {
                sink.complex(new RollbackEffect.EntitySpawn(location, entityType,
                        row.getString("entity_nbt")), occurred, id);
            } else {
                UUID entityId = row.getUUID("entity_id");
                sink.complex(new RollbackEffect.EntityRemove(location, entityType,
                        entityId == null ? null : entityId.toString()), occurred, id);
            }
            return;
        }
        // Non-rollbackable row (the event filter should exclude these);
        // advance the cursor past it.
        sink.skip(occurred, id);
    }

    private static String chTimestamp(Instant when) {
        long s = when.getEpochSecond();
        int ms = when.getNano() / 1_000_000;
        return "toDateTime64('" + s + "." + String.format("%03d", ms) + "', 3, 'UTC')";
    }

    // Post-filter fallback (#32): rows scanned per query when part of
    // the predicate tree (item.name / lore / enchants — opaque BSON on
    // CH) must be evaluated in memory. The pushable predicates (player,
    // time, radius, event) bound the scan in practice; this bounds it
    // in pathology.
    private static final int POST_FILTER_SCAN_CAP = 20_000;

    private QueryResult runQuery(QueryRequest request, boolean includeHeavy) {
        List<QueryPredicate> predicates = new ArrayList<>(request.predicates());
        if (request.flags().contains(Flag.NO_CHAT)) {
            predicates.add(new QueryPredicate.Exists(RecordFields.MESSAGE, false));
        }

        // Full pushdown first; on an unsupported path, split the
        // top-level AND list into pushable SQL + an in-memory residual
        // evaluated against the decoded records (#32). Residual fields
        // live inside the heavy blobs, so the scan hydrates them even
        // for summary queries.
        String where;
        List<QueryPredicate> residual = List.of();
        try {
            where = predicateToSql.translate(predicates);
        } catch (PredicateToSql.UnsupportedPredicateException unsupported) {
            List<QueryPredicate> pushable = new ArrayList<>();
            List<QueryPredicate> inMemory = new ArrayList<>();
            for (QueryPredicate predicate : predicates) {
                try {
                    predicateToSql.translate(List.of(predicate));
                    pushable.add(predicate);
                } catch (PredicateToSql.UnsupportedPredicateException ex) {
                    inMemory.add(predicate);
                }
            }
            residual = inMemory;
            where = predicateToSql.translate(pushable);
        }
        boolean postFilter = !residual.isEmpty();
        boolean hydrate = includeHeavy || postFilter;

        StringBuilder sql = new StringBuilder("SELECT ")
                .append(hydrate ? fullSelect : summarySelect)
                .append(" FROM ").append(qualifiedTable);
        if (!where.isEmpty()) {
            sql.append(" WHERE ").append(where);
        }
        sql.append(" ORDER BY occurred ")
                .append(request.sort() == Sort.OLDEST_FIRST ? "ASC" : "DESC");
        sql.append(" LIMIT ").append(postFilter ? POST_FILTER_SCAN_CAP : request.limit());

        List<GenericRecord> rows;
        try {
            rows = client.queryAll(sql.toString());
        } catch (RuntimeException ex) {
            throw new RuntimeException("ClickHouse query failed: " + ex.getMessage(), ex);
        }
        List<EventRecord> records = new ArrayList<>(postFilter ? 64 : rows.size());
        for (GenericRecord row : rows) {
            EventRecord record = decodeRow(row, hydrate);
            if (record == null) {
                continue;
            }
            if (postFilter && !PredicateEvaluator.matchesAll(residual, record)) {
                continue;
            }
            records.add(record);
            if (postFilter && records.size() >= request.limit()) {
                break;
            }
        }
        List<QueryResult.RecordAggregation> aggregations =
                request.grouping() && !request.flags().contains(Flag.NO_GROUP)
                        ? aggregate(records)
                        : List.of();
        return new QueryResult(records, aggregations);
    }

    private EventRecord decodeRow(GenericRecord row, boolean includeHeavy) {
        String event = row.getString("event");
        Class<? extends EventRecord> clazz = EventCatalog.recordClassOf(event);
        if (clazz == null) {
            return null;
        }
        UUID id = net.medievalrp.spyglass.api.util.EventIds.uuidOf(row.getLong("id"));
        Instant occurred = row.getInstant("occurred");
        Instant expiresAt = row.getInstant("expires_at");
        Origin origin = readOrigin(row);
        Source source = readSource(row);
        BlockLocation location = readLocation(row);
        String server = row.getString("server");
        String target = row.getString("target");

        if (clazz == BlockBreakRecord.class) {
            return new BlockBreakRecord(id, event, occurred, expiresAt,
                    origin, source, location, server, target,
                    includeHeavy ? BsonBlobs.decodeBlockSnapshotFromColumns(
                            row.getString("before_material"),
                            row.getString("before_blockdata"),
                            row.getString("before_extras")) : null,
                    includeHeavy ? BsonBlobs.decodeBlockSnapshotFromColumns(
                            row.getString("after_material"),
                            row.getString("after_blockdata"),
                            row.getString("after_extras")) : null);
        }
        if (clazz == BlockPlaceRecord.class) {
            return new BlockPlaceRecord(id, event, occurred, expiresAt,
                    origin, source, location, server, target,
                    includeHeavy ? BsonBlobs.decodeBlockSnapshotFromColumns(
                            row.getString("before_material"),
                            row.getString("before_blockdata"),
                            row.getString("before_extras")) : null,
                    includeHeavy ? BsonBlobs.decodeBlockSnapshotFromColumns(
                            row.getString("after_material"),
                            row.getString("after_blockdata"),
                            row.getString("after_extras")) : null);
        }
        if (clazz == BlockUseRecord.class) {
            return new BlockUseRecord(id, event, occurred, expiresAt,
                    origin, source, location, server, target);
        }
        if (clazz == RollbackOpRecord.class) {
            // mode rides in the common `target` column; the reference
            // blob is heavy-class (only full reads need to expand ops).
            return new RollbackOpRecord(id, event, occurred, expiresAt,
                    origin, source, location, server, target,
                    includeHeavy ? row.getString("op_reference") : null);
        }
        if (clazz == ChatRecord.class) {
            return new ChatRecord(id, event, occurred, expiresAt,
                    origin, source, location, server, target,
                    row.getString("message"),
                    readUuidList(row, "recipients"),
                    readStringMap(row, "extensions"));
        }
        if (clazz == CommandRecord.class) {
            return new CommandRecord(id, event, occurred, expiresAt,
                    origin, source, location, server, target,
                    row.getString("command_line"));
        }
        if (clazz == JoinRecord.class) {
            return new JoinRecord(id, event, occurred, expiresAt,
                    origin, source, location, server, target,
                    row.getString("address"));
        }
        if (clazz == QuitRecord.class) {
            return new QuitRecord(id, event, occurred, expiresAt,
                    origin, source, location, server, target);
        }
        if (clazz == ContainerDepositRecord.class) {
            return new ContainerDepositRecord(id, event, occurred, expiresAt,
                    origin, source, location, server, target,
                    row.getString("container_type"),
                    row.getInteger("slot"),
                    row.getInteger("amount"),
                    includeHeavy ? BsonBlobs.decodeStoredItem(row.getString("before_item")) : null,
                    includeHeavy ? BsonBlobs.decodeStoredItem(row.getString("after_item")) : null);
        }
        if (clazz == ContainerWithdrawRecord.class) {
            return new ContainerWithdrawRecord(id, event, occurred, expiresAt,
                    origin, source, location, server, target,
                    row.getString("container_type"),
                    row.getInteger("slot"),
                    row.getInteger("amount"),
                    includeHeavy ? BsonBlobs.decodeStoredItem(row.getString("before_item")) : null,
                    includeHeavy ? BsonBlobs.decodeStoredItem(row.getString("after_item")) : null);
        }
        if (clazz == ContainerInteractRecord.class) {
            return new ContainerInteractRecord(id, event, occurred, expiresAt,
                    origin, source, location, server, target);
        }
        if (clazz == ItemDropRecord.class) {
            return new ItemDropRecord(id, event, occurred, expiresAt,
                    origin, source, location, server, target,
                    row.getInteger("amount"),
                    includeHeavy ? BsonBlobs.decodeStoredItem(row.getString("item")) : null);
        }
        if (clazz == ItemPickupRecord.class) {
            return new ItemPickupRecord(id, event, occurred, expiresAt,
                    origin, source, location, server, target,
                    row.getInteger("amount"),
                    includeHeavy ? BsonBlobs.decodeStoredItem(row.getString("item")) : null);
        }
        if (clazz == TeleportRecord.class) {
            BlockLocation from = readOptionalLocation(row, "teleport_from_");
            BlockLocation to = readOptionalLocation(row, "teleport_to_");
            return new TeleportRecord(id, event, occurred, expiresAt,
                    origin, source, location, server, target,
                    from, to,
                    row.getString("teleport_cause"));
        }
        if (clazz == EntityDeathRecord.class) {
            return new EntityDeathRecord(id, event, occurred, expiresAt,
                    origin, source, location, server, target,
                    row.getString("entity_type"),
                    row.getUUID("entity_id"),
                    row.getString("entity_killer_type"),
                    row.getString("entity_damage_cause"),
                    includeHeavy ? row.getString("entity_nbt") : null);
        }
        if (clazz == EntityHitRecord.class) {
            return new EntityHitRecord(id, event, occurred, expiresAt,
                    origin, source, location, server, target,
                    row.getString("entity_type"),
                    row.getUUID("entity_id"),
                    row.getDouble("entity_damage"),
                    row.getByte("entity_projectile") != 0,
                    row.getString("entity_projectile_type"));
        }
        if (clazz == EntityMountRecord.class) {
            return new EntityMountRecord(id, event, occurred, expiresAt,
                    origin, source, location, server, target,
                    row.getString("entity_type"),
                    row.getUUID("entity_id"),
                    row.getByte("entity_dismount") != 0);
        }
        if (clazz == EntityNameRecord.class) {
            return new EntityNameRecord(id, event, occurred, expiresAt,
                    origin, source, location, server, target,
                    row.getString("entity_type"),
                    row.getUUID("entity_id"),
                    row.getString("entity_old_name"),
                    row.getString("entity_new_name"));
        }
        return null;
    }

    private Origin readOrigin(GenericRecord row) {
        return new Origin(row.getString("origin_kind"), row.getString("origin_detail"));
    }

    private Source readSource(GenericRecord row) {
        BlockLocation cmdBlock = readOptionalLocation(row, "source_command_block_");
        return new Source(
                row.getString("source_kind"),
                row.getUUID("source_player_id"),
                row.getString("source_player_name"),
                row.getUUID("source_entity_id"),
                row.getString("source_entity_type"),
                row.getString("source_plugin_name"),
                cmdBlock,
                row.getString("source_description"));
    }

    private BlockLocation readLocation(GenericRecord row) {
        return new BlockLocation(
                row.getUUID("location_world_id"),
                row.getString("location_world_name"),
                row.getInteger("location_x"),
                row.getInteger("location_y"),
                row.getInteger("location_z"));
    }

    private BlockLocation readOptionalLocation(GenericRecord row, String prefix) {
        UUID worldId = row.getUUID(prefix + "world_id");
        if (worldId == null) {
            return null;
        }
        return new BlockLocation(
                worldId,
                row.getString(prefix + "world_name"),
                row.getInteger(prefix + "x"),
                row.getInteger(prefix + "y"),
                row.getInteger(prefix + "z"));
    }

    /**
     * Reads a ClickHouse {@code Map(String,String)} column. The client decodes
     * a Map column to a {@link Map}; a null/absent/empty column yields an empty
     * map (records written before the column existed read as empty).
     */
    private Map<String, String> readStringMap(GenericRecord row, String column) {
        Object raw = row.getObject(column);
        if (!(raw instanceof Map<?, ?> map) || map.isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = new HashMap<>(map.size());
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                out.put(entry.getKey().toString(), entry.getValue().toString());
            }
        }
        return Map.copyOf(out);
    }

    private List<UUID> readUuidList(GenericRecord row, String column) {
        List<?> raw = row.getList(column);
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<UUID> out = new ArrayList<>(raw.size());
        for (Object element : raw) {
            if (element instanceof UUID uuid) {
                out.add(uuid);
            } else if (element != null) {
                out.add(UUID.fromString(element.toString()));
            }
        }
        return out;
    }

    @Override
    public void close() {
        try {
            client.close();
        } catch (Exception ignored) {
            // shutdown best-effort
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static LocalDateTime toLocalDateTime(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    @SafeVarargs
    private static List<String> concat(List<String>... lists) {
        List<String> out = new ArrayList<>();
        for (List<String> list : lists) {
            out.addAll(list);
        }
        return out;
    }

    private List<QueryResult.RecordAggregation> aggregate(List<EventRecord> records) {
        Map<String, Long> counts = new HashMap<>();
        Map<String, EventRecord> sample = new HashMap<>();
        for (EventRecord record : records) {
            String key = record.event() + "|" + record.sourceName() + "|" + record.target() + "|"
                    + record.occurred().atZone(java.time.ZoneOffset.UTC).toLocalDate();
            counts.merge(key, 1L, Long::sum);
            sample.putIfAbsent(key, record);
        }
        return counts.entrySet().stream()
                .map(entry -> new QueryResult.RecordAggregation(sample.get(entry.getKey()), entry.getValue()))
                .sorted(Comparator.comparing((QueryResult.RecordAggregation aggregation)
                        -> aggregation.sample().occurred()).reversed())
                .toList();
    }

    // ===== Test / bench helpers ================================

    public long count() {
        List<GenericRecord> rows = client.queryAll("SELECT count() AS c FROM " + qualifiedTable);
        return rows.isEmpty() ? 0L : rows.get(0).getLong("c");
    }

    public long compressedBytes() {
        return systemPartsSum("bytes_on_disk");
    }

    public long uncompressedBytes() {
        return systemPartsSum("data_uncompressed_bytes");
    }

    private long systemPartsSum(String column) {
        // {@code database}/{@code table} come from config, but we still
        // refuse anything outside ClickHouse's identifier alphabet here:
        // a misconfigured YAML with a stray quote would otherwise become
        // a self-inflicted SQL-injection in this helper.
        requireIdentifier(database);
        requireIdentifier(table);
        String sql = "SELECT sum(" + column + ") AS c FROM system.parts "
                + "WHERE active AND database = '" + database + "' AND table = '" + table + "'";
        List<GenericRecord> rows = client.queryAll(sql);
        return rows.isEmpty() ? 0L : rows.get(0).getLong("c");
    }

    private static void requireIdentifier(String value) {
        if (value == null || value.isEmpty()) {
            throw new IllegalStateException("ClickHouse identifier is blank");
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z')
                    || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9')
                    || c == '_';
            if (!ok) {
                throw new IllegalStateException(
                        "ClickHouse identifier contains illegal character: " + value);
            }
        }
    }

    public void optimize() {
        try (CommandResponse ignored = client.execute(
                "OPTIMIZE TABLE " + qualifiedTable + " FINAL").get(120, TimeUnit.SECONDS)) {
            // ack
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (Exception ex) {
            throw new RuntimeException("OPTIMIZE failed: " + ex.getMessage(), ex);
        }
    }
}
