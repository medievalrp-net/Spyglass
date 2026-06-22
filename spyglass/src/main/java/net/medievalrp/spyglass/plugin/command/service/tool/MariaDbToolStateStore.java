package net.medievalrp.spyglass.plugin.command.service.tool;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import net.medievalrp.spyglass.plugin.storage.MariaDbRecordStore;
import org.jetbrains.annotations.ApiStatus;

/**
 * MariaDB / MySQL-backed {@link ToolStateStore} - a small {@code (player_id,
 * enabled_at)} table in the shared Spyglass database. Point operations on a
 * {@code VARCHAR(36)} primary key, so {@link #enable} / {@link #disable} are
 * O(log N) with no scan. Models {@code SqliteToolStateStore}.
 */
@ApiStatus.Internal
public final class MariaDbToolStateStore implements ToolStateStore {

    private final MariaDbRecordStore store;

    public MariaDbToolStateStore(MariaDbRecordStore store) {
        this.store = store;
        store.withWriteConnection(conn -> {
            try (var st = conn.createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS tool_states ("
                        + "player_id VARCHAR(36) NOT NULL PRIMARY KEY, enabled_at BIGINT) "
                        + "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            }
            return null;
        });
    }

    @Override
    public Collection<UUID> loadActive() {
        return store.withReadConnection(conn -> {
            List<UUID> out = new ArrayList<>();
            try (var st = conn.createStatement();
                 var rs = st.executeQuery("SELECT player_id FROM tool_states")) {
                while (rs.next()) {
                    out.add(UUID.fromString(rs.getString("player_id")));
                }
            }
            return out;
        });
    }

    @Override
    public void enable(UUID playerId) {
        store.withWriteConnection(conn -> {
            try (var ps = conn.prepareStatement(
                    "REPLACE INTO tool_states (player_id, enabled_at) VALUES (?, ?)")) {
                ps.setString(1, playerId.toString());
                ps.setLong(2, System.currentTimeMillis());
                ps.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public void disable(UUID playerId) {
        store.withWriteConnection(conn -> {
            try (var ps = conn.prepareStatement("DELETE FROM tool_states WHERE player_id = ?")) {
                ps.setString(1, playerId.toString());
                ps.executeUpdate();
            }
            return null;
        });
    }
}
