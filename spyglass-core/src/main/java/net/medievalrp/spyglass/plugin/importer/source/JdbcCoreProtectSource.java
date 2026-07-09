package net.medievalrp.spyglass.plugin.importer.source;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.jetbrains.annotations.Nullable;

/**
 * Shared JDBC backend that talks to either SQLite or MySQL CoreProtect
 * databases. {@link SqliteSource} and {@link MysqlSource} are thin
 * factories that just produce the right {@link Connection}.
 *
 * <p>All streaming queries use {@code TYPE_FORWARD_ONLY,
 * CONCUR_READ_ONLY, fetchSize=10000} so result sets cursor through the
 * source rather than buffering the full table in RAM. The shared
 * connection is set to {@code autoCommit=false} so MySQL honours the
 * fetch size.
 */
public final class JdbcCoreProtectSource implements CoreProtectSource {

    private static final int FETCH_SIZE = 10_000;

    private final Connection connection;
    private final String label;
    @Nullable private Map<Integer, String> blockDataMap;

    public JdbcCoreProtectSource(Connection connection, String label) {
        this.connection = connection;
        this.label = label;
    }

    /**
     * Lazy-load {@code co_blockdata_map} into memory so block-row
     * streaming can resolve token ids without per-row joins. The table
     * is small in practice (a few thousand rows even on multi-year
     * histories) and the per-stream cost of pulling it once is much
     * cheaper than a JOIN for every of millions of {@code co_block} rows.
     */
    private Map<Integer, String> blockDataMap() throws IOException {
        if (blockDataMap != null) return blockDataMap;
        Map<Integer, String> m = new HashMap<>();
        try (Statement s = connection.createStatement();
             ResultSet rs = s.executeQuery("SELECT id, data FROM co_blockdata_map")) {
            while (rs.next()) {
                int id = rs.getInt("id");
                String data = rs.getString("data");
                if (data != null && !data.isEmpty()) {
                    m.put(id, data);
                }
            }
        } catch (SQLException ex) {
            if (isMissingTable(ex)) {
                // CoreProtect 18+ always has this table; SchemaProbe
                // already enforced that. If it's missing here we'd
                // rather emit empty blockdata than crash.
                blockDataMap = Map.of();
                return blockDataMap;
            }
            throw new IOException("Failed loading co_blockdata_map from "
                    + label + ": " + ex.getMessage(), ex);
        }
        blockDataMap = Map.copyOf(m);
        return blockDataMap;
    }

    @Override
    public List<String> worldNames() throws IOException {
        // Union the worlds referenced by every event table that has a
        // wid column. Some installs disable specific event types, so we
        // can't lean on co_block alone.
        Set<String> names = new LinkedHashSet<>();
        String[] tables = {"co_block", "co_session", "co_chat",
                "co_command", "co_container", "co_item"};
        for (String t : tables) {
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery(
                         "SELECT DISTINCT cw.world FROM " + t + " ev "
                                 + "JOIN co_world cw ON cw.id = ev.wid "
                                 + "WHERE cw.world IS NOT NULL AND cw.world <> ''")) {
                while (rs.next()) {
                    names.add(rs.getString(1));
                }
            } catch (SQLException ex) {
                // A missing table is fine (operator disabled the event
                // type). Anything else is fatal.
                if (!isMissingTable(ex)) {
                    throw new IOException("Failed reading worlds from "
                            + t + " on " + label + ": " + ex.getMessage(), ex);
                }
            }
        }
        return new ArrayList<>(names);
    }

    @Override
    public void streamBlockRows(Consumer<CoreProtectBlockRow> consumer) throws IOException {
        // Action 3 (kill) overloads the type column: type=0 is a player
        // kill (data is co_user.rowid of the victim), type≠0 is an
        // entity kill (type resolves via co_entity_map). Branch the
        // join with two LEFT JOINs and pick whichever populated.
        //
        // NOTE: cb.blockdata is read raw (UTF-8 string of comma-separated
        // co_blockdata_map ids). We resolve it in Java against the
        // pre-loaded blockDataMap rather than joining, because the
        // value is multi-token, not a single FK.
        Map<Integer, String> blockData = blockDataMap();
        String sql = ""
                + "SELECT cb.rowid, cb.time, "
                + "       cw.world, cb.x, cb.y, cb.z, "
                + "       cm.material, "
                + "       cb.blockdata AS blockdata_raw, "
                + "       cb.action, cb.rolled_back, "
                + "       cu.user, cu.uuid, "
                + "       em.entity AS killed_entity, "
                + "       vu.user AS killed_player, "
                + "       vu.uuid AS killed_player_uuid "
                + "FROM co_block cb "
                + "JOIN co_world cw ON cw.id = cb.wid "
                + "LEFT JOIN co_material_map cm "
                + "       ON cm.id = cb.type AND cb.action <> 3 "
                + "LEFT JOIN co_entity_map em "
                + "       ON em.id = cb.type AND cb.action = 3 AND cb.type <> 0 "
                + "LEFT JOIN co_user vu "
                + "       ON vu.rowid = cb.data AND cb.action = 3 AND cb.type = 0 "
                + "LEFT JOIN co_user cu ON cu.rowid = cb.user "
                + "ORDER BY cb.rowid";
        runQuery(sql, rs -> {
            String materialName = rs.getString("material");
            String resolvedBlockData = BlockDataResolver.resolve(
                    materialName, rs.getString("blockdata_raw"), blockData);
            consumer.accept(new CoreProtectBlockRow(
                    rs.getLong("rowid"),
                    rs.getLong("time"),
                    rs.getString("world"),
                    rs.getInt("x"), rs.getInt("y"), rs.getInt("z"),
                    materialName,
                    resolvedBlockData,
                    rs.getInt("action"),
                    rs.getInt("rolled_back") != 0,
                    rs.getString("user"),
                    parseUuidOrNull(rs.getString("uuid")),
                    rs.getString("killed_entity"),
                    rs.getString("killed_player"),
                    parseUuidOrNull(rs.getString("killed_player_uuid"))));
        }, "co_block");
    }

    @Override
    public void streamSessionRows(Consumer<CoreProtectSessionRow> consumer) throws IOException {
        String sql = ""
                + "SELECT s.rowid, s.time, cw.world, s.x, s.y, s.z, s.action, "
                + "       cu.user, cu.uuid "
                + "FROM co_session s "
                + "JOIN co_world cw ON cw.id = s.wid "
                + "LEFT JOIN co_user cu ON cu.rowid = s.user "
                + "ORDER BY s.rowid";
        runQuery(sql, rs -> consumer.accept(new CoreProtectSessionRow(
                rs.getLong("rowid"),
                rs.getLong("time"),
                rs.getString("world"),
                rs.getInt("x"), rs.getInt("y"), rs.getInt("z"),
                rs.getInt("action"),
                rs.getString("user"),
                parseUuidOrNull(rs.getString("uuid")))), "co_session");
    }

    @Override
    public void streamChatRows(Consumer<CoreProtectChatRow> consumer) throws IOException {
        runChatLikeQuery("co_chat", consumer);
    }

    @Override
    public void streamCommandRows(Consumer<CoreProtectChatRow> consumer) throws IOException {
        runChatLikeQuery("co_command", consumer);
    }

    private void runChatLikeQuery(String table, Consumer<CoreProtectChatRow> consumer)
            throws IOException {
        String sql = ""
                + "SELECT t.rowid, t.time, cw.world, t.x, t.y, t.z, t.message, "
                + "       cu.user, cu.uuid "
                + "FROM " + table + " t "
                + "JOIN co_world cw ON cw.id = t.wid "
                + "LEFT JOIN co_user cu ON cu.rowid = t.user "
                + "ORDER BY t.rowid";
        runQuery(sql, rs -> consumer.accept(new CoreProtectChatRow(
                rs.getLong("rowid"),
                rs.getLong("time"),
                rs.getString("world"),
                rs.getInt("x"), rs.getInt("y"), rs.getInt("z"),
                rs.getString("message"),
                rs.getString("user"),
                parseUuidOrNull(rs.getString("uuid")))), table);
    }

    @Override
    public void streamContainerRows(Consumer<CoreProtectContainerRow> consumer) throws IOException {
        String sql = ""
                + "SELECT c.rowid, c.time, cw.world, c.x, c.y, c.z, "
                + "       cm.material, c.amount, c.action, c.rolled_back, "
                + "       c.metadata, cu.user, cu.uuid "
                + "FROM co_container c "
                + "JOIN co_world cw ON cw.id = c.wid "
                + "JOIN co_material_map cm ON cm.id = c.type "
                + "LEFT JOIN co_user cu ON cu.rowid = c.user "
                + "ORDER BY c.rowid";
        runQuery(sql, rs -> consumer.accept(new CoreProtectContainerRow(
                rs.getLong("rowid"),
                rs.getLong("time"),
                rs.getString("world"),
                rs.getInt("x"), rs.getInt("y"), rs.getInt("z"),
                rs.getString("material"),
                rs.getInt("amount"),
                rs.getInt("action"),
                rs.getInt("rolled_back") != 0,
                rs.getBytes("metadata"),
                rs.getString("user"),
                parseUuidOrNull(rs.getString("uuid")))), "co_container");
    }

    @Override
    public void streamItemRows(Consumer<CoreProtectItemRow> consumer) throws IOException {
        // co_item has the same shape as co_container but with a "data"
        // BLOB column for the metadata (yes, the column name is "data"
        // here and "metadata" in co_container — CoreProtect quirk).
        String sql = ""
                + "SELECT i.rowid, i.time, cw.world, i.x, i.y, i.z, "
                + "       cm.material, i.amount, i.action, i.rolled_back, "
                + "       i.data, cu.user, cu.uuid "
                + "FROM co_item i "
                + "JOIN co_world cw ON cw.id = i.wid "
                + "JOIN co_material_map cm ON cm.id = i.type "
                + "LEFT JOIN co_user cu ON cu.rowid = i.user "
                + "ORDER BY i.rowid";
        runQuery(sql, rs -> consumer.accept(new CoreProtectItemRow(
                rs.getLong("rowid"),
                rs.getLong("time"),
                rs.getString("world"),
                rs.getInt("x"), rs.getInt("y"), rs.getInt("z"),
                rs.getString("material"),
                rs.getInt("amount"),
                rs.getInt("action"),
                rs.getInt("rolled_back") != 0,
                rs.getBytes("data"),
                rs.getString("user"),
                parseUuidOrNull(rs.getString("uuid")))), "co_item");
    }

    @FunctionalInterface
    private interface RowHandler {
        void accept(ResultSet rs) throws SQLException;
    }

    private void runQuery(String sql, RowHandler handler, String tableLabel) throws IOException {
        try (PreparedStatement ps = connection.prepareStatement(
                sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
            ps.setFetchSize(FETCH_SIZE);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    handler.accept(rs);
                }
            }
        } catch (SQLException ex) {
            if (isMissingTable(ex)) {
                // Operator disabled the event type. Empty stream, not
                // a fatal error.
                return;
            }
            throw new IOException("Failed streaming " + tableLabel + " from "
                    + label + ": " + ex.getMessage(), ex);
        }
    }

    private static boolean isMissingTable(SQLException ex) {
        String msg = ex.getMessage();
        if (msg == null) return false;
        String lower = msg.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("no such table") || lower.contains("doesn't exist");
    }

    @Nullable
    private static UUID parseUuidOrNull(@Nullable String raw) {
        if (raw == null || raw.isEmpty() || raw.equals("#unknown")) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    @Override
    public void close() throws IOException {
        try {
            connection.close();
        } catch (SQLException ex) {
            throw new IOException("Failed closing source " + label, ex);
        }
    }
}
