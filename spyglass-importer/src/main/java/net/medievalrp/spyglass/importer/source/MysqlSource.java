package net.medievalrp.spyglass.importer.source;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

/**
 * Factory for a {@link CoreProtectSource} backed by a live MySQL
 * CoreProtect database.
 *
 * <p>Connection string format: {@code mysql://user:password@host:port/database}.
 * The user only needs read privileges on the {@code co_*} tables.
 *
 * <p>Forward-only cursor + per-statement {@code fetchSize=10000} require
 * {@code useCursorFetch=true} on the connection URL, which we set for
 * the user — without it, MySQL Connector/J buffers the whole result set
 * client-side regardless of {@code setFetchSize}, OOMing on large
 * databases.
 */
public final class MysqlSource {

    private MysqlSource() {}

    /**
     * Parsed components of a {@code mysql://...} URL. Public so callers
     * (and tests) can validate parsing without opening a connection.
     */
    public record ConnectionSpec(String host, int port, String database,
                                 String user, String password) {

        public String jdbcUrl() {
            // Connector/J's required flags for cursor-based reads:
            //   useCursorFetch — actually honour fetchSize
            //   useServerPrepStmts — server-side prepares (paired with cursorFetch)
            //   netTimeoutForStreamingResults — keep cursor alive during long streams
            // Charset is left default; Connector/J negotiates utf8mb4 with
            // any recent MariaDB/MySQL server automatically.
            return "jdbc:mysql://" + host + ":" + port + "/" + database
                    + "?useCursorFetch=true"
                    + "&useServerPrepStmts=true"
                    + "&netTimeoutForStreamingResults=600";
        }
    }

    /** Parse a {@code mysql://user:password@host:port/database} URL. */
    public static ConnectionSpec parse(String url) {
        if (url == null || !url.startsWith("mysql://")) {
            throw new IllegalArgumentException(
                    "MySQL URL must start with mysql://, got: " + url);
        }
        String stripped = url.substring("mysql://".length());
        int atIdx = stripped.lastIndexOf('@');
        if (atIdx < 0) {
            throw new IllegalArgumentException(
                    "MySQL URL must contain user:password@, got: " + url);
        }
        String creds = stripped.substring(0, atIdx);
        String hostAndDb = stripped.substring(atIdx + 1);

        int colonIdx = creds.indexOf(':');
        if (colonIdx < 0) {
            throw new IllegalArgumentException(
                    "MySQL URL credentials must be user:password, got: " + creds);
        }
        String user = creds.substring(0, colonIdx);
        String password = creds.substring(colonIdx + 1);

        int slashIdx = hostAndDb.indexOf('/');
        if (slashIdx < 0) {
            throw new IllegalArgumentException(
                    "MySQL URL must include /database, got: " + hostAndDb);
        }
        String hostPort = hostAndDb.substring(0, slashIdx);
        String database = hostAndDb.substring(slashIdx + 1);

        String host;
        int port;
        int hostColon = hostPort.indexOf(':');
        if (hostColon < 0) {
            host = hostPort;
            port = 3306;
        } else {
            host = hostPort.substring(0, hostColon);
            try {
                port = Integer.parseInt(hostPort.substring(hostColon + 1));
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException(
                        "MySQL URL port is not a number: " + hostPort);
            }
        }
        if (database.isEmpty()) {
            throw new IllegalArgumentException("MySQL URL database is empty: " + url);
        }
        return new ConnectionSpec(host, port, database, user, password);
    }

    public static CoreProtectSource open(String url) throws IOException {
        ConnectionSpec spec = parse(url);
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException ex) {
            throw new IOException("mysql-connector-j driver missing from classpath", ex);
        }

        Properties props = new Properties();
        props.setProperty("user", spec.user());
        props.setProperty("password", spec.password());

        Connection connection;
        try {
            connection = DriverManager.getConnection(spec.jdbcUrl(), props);
            // Cursor fetch needs autoCommit=false and a transaction.
            connection.setAutoCommit(false);
            connection.setReadOnly(true);
            SchemaProbe.requireCoreProtect20Plus(connection);
        } catch (SQLException ex) {
            throw new IOException("Failed to open MySQL source "
                    + spec.user() + "@" + spec.host() + ":" + spec.port()
                    + "/" + spec.database() + ": " + ex.getMessage(), ex);
        }
        return new JdbcCoreProtectSource(connection,
                "mysql:" + spec.host() + "/" + spec.database());
    }
}
