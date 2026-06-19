package net.medievalrp.spyglass.plugin.salvage;

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
import net.medievalrp.spyglass.api.event.StoredItem;
import net.medievalrp.spyglass.plugin.storage.BsonBlobs;
import org.jetbrains.annotations.ApiStatus;

/**
 * ClickHouse-backed {@link SalvageStore}. A {@code ReplacingMergeTree(updated_at)}
 * keyed on {@code id}: a re-insert with a newer {@code updated_at} supersedes the
 * row (so an extraction's {@link #replaceItems} wins), and {@link #delete} writes
 * a {@code deleted=1} tombstone that {@code FINAL WHERE deleted = 0} reads hide.
 * Items are a {@code |}-joined list of {@link BsonBlobs#encodeStoredItem} blobs.
 * Modeled on {@code ClickHouseUndoStack}; self-creates its table.
 */
@ApiStatus.Internal
public final class ClickHouseSalvageStore implements SalvageStore {

    private static final DateTimeFormatter CH_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final UUID ZERO_UUID = new UUID(0L, 0L);

    private final Client client;
    private final String table;

    public ClickHouseSalvageStore(Client client, String database, long ttlDays) {
        this.client = client;
        this.table = "`" + database + "`.`spyglass_salvage`";
        ensureTable(ttlDays);
    }

    private void ensureTable(long ttlDays) {
        String ttl = ttlDays > 0
                ? "TTL toDateTime(captured_at) + INTERVAL " + ttlDays + " DAY\n"
                : "";
        execute("CREATE TABLE IF NOT EXISTS " + table + " (\n"
                + "  id UUID,\n"
                + "  rollback_op_id UUID,\n"
                + "  world_id UUID,\n"
                + "  world_name String,\n"
                + "  x Int32,\n  y Int32,\n  z Int32,\n"
                + "  container_type String,\n"
                + "  operator_name String,\n"
                + "  captured_at DateTime64(3),\n"
                + "  items String,\n"
                + "  deleted UInt8,\n"
                + "  updated_at DateTime64(3)\n"
                + ") ENGINE = ReplacingMergeTree(updated_at)\n"
                + "ORDER BY id\n"
                + ttl);
    }

    @Override
    public void save(SalvageSnapshot snapshot) {
        insert(snapshot, false);
    }

    @Override
    public List<SalvageSnapshot> list(int limit) {
        List<SalvageSnapshot> out = new ArrayList<>();
        for (GenericRecord row : client.queryAll(selectColumns()
                + " FROM " + table + " FINAL WHERE deleted = 0 "
                + "ORDER BY captured_at DESC LIMIT " + Math.max(1, limit))) {
            out.add(fromRow(row));
        }
        return out;
    }

    @Override
    public List<RollbackGroup> listRollbacks(int limit) {
        List<RollbackGroup> out = new ArrayList<>();
        for (GenericRecord r : client.queryAll(
                "SELECT rollback_op_id, count() AS c, any(operator_name) AS op, "
                        + "max(captured_at) AS latest FROM " + table + " FINAL WHERE deleted = 0 "
                        + "GROUP BY rollback_op_id ORDER BY latest DESC LIMIT " + Math.max(1, limit))) {
            out.add(new RollbackGroup(r.getUUID("rollback_op_id"), (int) r.getLong("c"),
                    r.getString("op"), r.getInstant("latest")));
        }
        return out;
    }

    @Override
    public List<SalvageSnapshot> listByRollback(UUID rollbackId, int limit) {
        List<SalvageSnapshot> out = new ArrayList<>();
        for (GenericRecord r : client.queryAll(selectColumns()
                + " FROM " + table + " FINAL WHERE deleted = 0 AND rollback_op_id = toUUID('"
                + rollbackId + "') ORDER BY captured_at DESC LIMIT " + Math.max(1, limit))) {
            out.add(fromRow(r));
        }
        return out;
    }

    @Override
    public Optional<SalvageSnapshot> get(UUID id) {
        List<GenericRecord> rows = client.queryAll(selectColumns()
                + " FROM " + table + " FINAL WHERE deleted = 0 AND id = toUUID('" + id + "') LIMIT 1");
        return rows.isEmpty() ? Optional.empty() : Optional.of(fromRow(rows.get(0)));
    }

    @Override
    public void replaceItems(UUID id, List<StoredItem> remaining) {
        get(id).ifPresent(current -> insert(current.withItems(remaining), false));
    }

    @Override
    public void delete(UUID id) {
        String now = chTimestamp(Instant.now());
        execute("INSERT INTO " + table + " " + columns() + " VALUES ("
                + "toUUID('" + id + "'), toUUID('" + ZERO_UUID + "'), toUUID('" + ZERO_UUID + "'), "
                + "'', 0, 0, 0, '', '', " + now + ", '', 1, " + now + ")");
    }

    private void insert(SalvageSnapshot s, boolean deleted) {
        execute("INSERT INTO " + table + " " + columns() + " VALUES ("
                + "toUUID('" + s.id() + "'), "
                + "toUUID('" + (s.rollbackOpId() == null ? ZERO_UUID : s.rollbackOpId()) + "'), "
                + "toUUID('" + s.worldId() + "'), " + escape(s.worldName()) + ", "
                + s.x() + ", " + s.y() + ", " + s.z() + ", "
                + escape(s.containerType()) + ", " + escape(s.operatorName()) + ", "
                + chTimestamp(s.capturedAt()) + ", " + escape(encodeItems(s.items())) + ", "
                + (deleted ? 1 : 0) + ", " + chTimestamp(Instant.now()) + ")");
    }

    private SalvageSnapshot fromRow(GenericRecord r) {
        return new SalvageSnapshot(
                r.getUUID("id"),
                r.getUUID("rollback_op_id"),
                r.getUUID("world_id"),
                r.getString("world_name"),
                (int) r.getLong("x"), (int) r.getLong("y"), (int) r.getLong("z"),
                r.getString("container_type"),
                r.getString("operator_name"),
                r.getInstant("captured_at"),
                decodeItems(r.getString("items")));
    }

    private static String selectColumns() {
        return "SELECT id, rollback_op_id, world_id, world_name, x, y, z, "
                + "container_type, operator_name, captured_at, items";
    }

    private static String columns() {
        return "(id, rollback_op_id, world_id, world_name, x, y, z, container_type, "
                + "operator_name, captured_at, items, deleted, updated_at)";
    }

    // Items as a '|'-joined list of base64 blobs. The base64 alphabet never
    // contains '|', so the split is unambiguous.
    static String encodeItems(List<StoredItem> items) {
        StringBuilder sb = new StringBuilder();
        for (StoredItem item : items) {
            String enc = BsonBlobs.encodeStoredItem(item);
            if (enc != null) {
                if (sb.length() > 0) {
                    sb.append('|');
                }
                sb.append(enc);
            }
        }
        return sb.toString();
    }

    static List<StoredItem> decodeItems(String blob) {
        List<StoredItem> items = new ArrayList<>();
        if (blob == null || blob.isEmpty()) {
            return items;
        }
        for (String part : blob.split("\\|")) {
            if (!part.isEmpty()) {
                StoredItem item = BsonBlobs.decodeStoredItem(part);
                if (item != null) {
                    items.add(item);
                }
            }
        }
        return items;
    }

    private void execute(String sql) {
        try (CommandResponse ignored = client.execute(sql).get(30, TimeUnit.SECONDS)) {
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Salvage command interrupted", ie);
        } catch (Exception ex) {
            throw new RuntimeException("Salvage command failed: " + ex.getMessage(), ex);
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
