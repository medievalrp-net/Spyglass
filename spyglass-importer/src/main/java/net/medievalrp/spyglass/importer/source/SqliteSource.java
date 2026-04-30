package net.medievalrp.spyglass.importer.source;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.sqlite.SQLiteConfig;

/**
 * Factory for a {@link CoreProtectSource} backed by a SQLite file. The
 * file is opened read-only via {@link SQLiteConfig#setReadOnly}, the
 * schema is probed against {@link SchemaProbe#requireCoreProtect20Plus},
 * and the resulting {@link Connection} is handed to a shared
 * {@link JdbcCoreProtectSource}.
 */
public final class SqliteSource {

    private SqliteSource() {}

    public static CoreProtectSource open(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IOException("--source SQLite file does not exist: " + file);
        }
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException ex) {
            throw new IOException("sqlite-jdbc driver missing from classpath", ex);
        }
        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(true);
        config.setSharedCache(true);
        String url = "jdbc:sqlite:" + file.toAbsolutePath();
        Connection connection;
        try {
            connection = DriverManager.getConnection(url, config.toProperties());
            connection.setAutoCommit(false);
            SchemaProbe.requireCoreProtect20Plus(connection);
        } catch (SQLException ex) {
            throw new IOException("Failed to open SQLite source " + file
                    + ": " + ex.getMessage(), ex);
        }
        return new JdbcCoreProtectSource(connection, "sqlite:" + file.getFileName());
    }
}
