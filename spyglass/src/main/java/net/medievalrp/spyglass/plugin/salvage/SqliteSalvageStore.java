package net.medievalrp.spyglass.plugin.salvage;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.medievalrp.spyglass.api.event.StoredItem;
import net.medievalrp.spyglass.plugin.storage.BsonBlobs;
import net.medievalrp.spyglass.plugin.storage.SqliteRecordStore;
import org.jetbrains.annotations.ApiStatus;

/**
 * SQLite-backed {@link SalvageStore}. A row per captured container
 * inventory in the shared Spyglass database, keyed on {@code id} so an
 * extraction's {@link #replaceItems} overwrites in place and
 * {@link #delete} removes the emptied snapshot. Items are a {@code |}-
 * joined list of {@link BsonBlobs#encodeStoredItem} blobs — the base64
 * alphabet never contains {@code |}, so the split is unambiguous.
 * Modeled on {@code ClickHouseSalvageStore}; self-creates its table.
 */
@ApiStatus.Internal
public final class SqliteSalvageStore implements SalvageStore {

    private final SqliteRecordStore store;
    private final long ttlMs;

    public SqliteSalvageStore(SqliteRecordStore store, long ttlDays) {
        this.store = store;
        this.ttlMs = ttlDays > 0 ? java.util.concurrent.TimeUnit.DAYS.toMillis(ttlDays) : 0L;
        store.withWriteConnection(conn -> {
            try (var st = conn.createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS salvage ("
                        + "id TEXT PRIMARY KEY, "
                        + "rollback_op_id TEXT, "
                        + "world_id TEXT, world_name TEXT, "
                        + "x INTEGER, y INTEGER, z INTEGER, "
                        + "container_type TEXT, operator_name TEXT, "
                        + "captured_at INTEGER, items TEXT)");
                st.execute("CREATE INDEX IF NOT EXISTS idx_salvage_captured ON salvage(captured_at)");
                st.execute("CREATE INDEX IF NOT EXISTS idx_salvage_rollback ON salvage(rollback_op_id)");
            }
            return null;
        });
    }

    @Override
    public void save(SalvageSnapshot snapshot) {
        store.withWriteConnection(conn -> {
            try (var ps = conn.prepareStatement("INSERT OR REPLACE INTO salvage "
                    + "(id, rollback_op_id, world_id, world_name, x, y, z, "
                    + "container_type, operator_name, captured_at, items) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                ps.setString(1, snapshot.id().toString());
                ps.setString(2, snapshot.rollbackOpId() == null ? null : snapshot.rollbackOpId().toString());
                ps.setString(3, snapshot.worldId() == null ? null : snapshot.worldId().toString());
                ps.setString(4, snapshot.worldName());
                ps.setInt(5, snapshot.x());
                ps.setInt(6, snapshot.y());
                ps.setInt(7, snapshot.z());
                ps.setString(8, snapshot.containerType());
                ps.setString(9, snapshot.operatorName());
                ps.setLong(10, snapshot.capturedAt().toEpochMilli());
                ps.setString(11, encodeItems(snapshot.items()));
                ps.executeUpdate();
            }
            pruneExpired(conn);
            return null;
        });
    }

    @Override
    public List<SalvageSnapshot> list(int limit) {
        return store.withReadConnection(conn -> {
            List<SalvageSnapshot> out = new ArrayList<>();
            try (var ps = conn.prepareStatement(selectColumns()
                    + " FROM salvage ORDER BY captured_at DESC LIMIT ?")) {
                ps.setInt(1, Math.max(1, limit));
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(fromRow(rs));
                    }
                }
            }
            return out;
        });
    }

    @Override
    public List<RollbackGroup> listRollbacks(int limit) {
        return store.withReadConnection(conn -> {
            List<RollbackGroup> out = new ArrayList<>();
            try (var ps = conn.prepareStatement("SELECT rollback_op_id, count(*) AS c, "
                    + "max(operator_name) AS op, max(captured_at) AS latest FROM salvage "
                    + "WHERE rollback_op_id IS NOT NULL "
                    + "GROUP BY rollback_op_id ORDER BY latest DESC LIMIT ?")) {
                ps.setInt(1, Math.max(1, limit));
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String rollbackId = rs.getString("rollback_op_id");
                        out.add(new RollbackGroup(UUID.fromString(rollbackId), rs.getInt("c"),
                                rs.getString("op"), Instant.ofEpochMilli(rs.getLong("latest"))));
                    }
                }
            }
            return out;
        });
    }

    @Override
    public List<SalvageSnapshot> listByRollback(UUID rollbackId, int limit) {
        return store.withReadConnection(conn -> {
            List<SalvageSnapshot> out = new ArrayList<>();
            try (var ps = conn.prepareStatement(selectColumns()
                    + " FROM salvage WHERE rollback_op_id = ? ORDER BY captured_at DESC LIMIT ?")) {
                ps.setString(1, rollbackId.toString());
                ps.setInt(2, Math.max(1, limit));
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(fromRow(rs));
                    }
                }
            }
            return out;
        });
    }

    @Override
    public Optional<SalvageSnapshot> get(UUID id) {
        return store.withReadConnection(conn -> {
            try (var ps = conn.prepareStatement(selectColumns() + " FROM salvage WHERE id = ? LIMIT 1")) {
                ps.setString(1, id.toString());
                try (var rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(fromRow(rs)) : Optional.<SalvageSnapshot>empty();
                }
            }
        });
    }

    @Override
    public void replaceItems(UUID id, List<StoredItem> remaining) {
        get(id).ifPresent(current -> save(current.withItems(remaining)));
    }

    @Override
    public void delete(UUID id) {
        store.withWriteConnection(conn -> {
            try (var ps = conn.prepareStatement("DELETE FROM salvage WHERE id = ?")) {
                ps.setString(1, id.toString());
                ps.executeUpdate();
            }
            return null;
        });
    }

    private void pruneExpired(java.sql.Connection conn) throws SQLException {
        if (ttlMs <= 0) {
            return;
        }
        try (var ps = conn.prepareStatement("DELETE FROM salvage WHERE captured_at < ?")) {
            ps.setLong(1, System.currentTimeMillis() - ttlMs);
            ps.executeUpdate();
        }
    }

    private static String selectColumns() {
        return "SELECT id, rollback_op_id, world_id, world_name, x, y, z, "
                + "container_type, operator_name, captured_at, items";
    }

    private static SalvageSnapshot fromRow(ResultSet rs) throws SQLException {
        String rollbackId = rs.getString("rollback_op_id");
        String worldId = rs.getString("world_id");
        return new SalvageSnapshot(
                UUID.fromString(rs.getString("id")),
                rollbackId == null ? null : UUID.fromString(rollbackId),
                worldId == null ? null : UUID.fromString(worldId),
                rs.getString("world_name"),
                rs.getInt("x"), rs.getInt("y"), rs.getInt("z"),
                rs.getString("container_type"),
                rs.getString("operator_name"),
                Instant.ofEpochMilli(rs.getLong("captured_at")),
                decodeItems(rs.getString("items")));
    }

    // Items as a '|'-joined list of base64 blobs; the base64 alphabet never
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
}
