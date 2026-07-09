package net.medievalrp.spyglass.plugin.importer.source;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Probes a CoreProtect database for the schema features Spyglass requires.
 * The importer targets CoreProtect 20+, which means {@code co_blockdata_map}
 * must exist (added in 18.0). Older databases fail fast with a clear
 * message rather than silently importing rows with degraded fidelity.
 */
public final class SchemaProbe {

    private SchemaProbe() {}

    /** Hard-error if the connected DB is older than CoreProtect 20. */
    public static void requireCoreProtect20Plus(Connection connection) throws SQLException {
        if (!hasTable(connection, "co_blockdata_map")) {
            throw new SQLException(
                    "CoreProtect database is missing co_blockdata_map. "
                            + "This importer targets CoreProtect 20+ "
                            + "(co_blockdata_map was added in 18.0). "
                            + "Upgrade the source DB before importing.");
        }
        if (!hasTable(connection, "co_block")) {
            throw new SQLException("CoreProtect database is missing co_block; "
                    + "is this actually a CoreProtect database?");
        }
        if (!hasTable(connection, "co_user")) {
            throw new SQLException("CoreProtect database is missing co_user.");
        }
        if (!hasTable(connection, "co_world")) {
            throw new SQLException("CoreProtect database is missing co_world.");
        }
        if (!hasTable(connection, "co_material_map")) {
            throw new SQLException("CoreProtect database is missing co_material_map.");
        }
    }

    private static boolean hasTable(Connection connection, String name) throws SQLException {
        // Works on both SQLite (sqlite_master) and MySQL (information_schema).
        // Try SQLite first; fall through to MySQL on syntax error.
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT name FROM sqlite_master WHERE type='table' AND name=?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException sqliteFail) {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT TABLE_NAME FROM information_schema.TABLES "
                            + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?")) {
                ps.setString(1, name);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        }
    }
}
