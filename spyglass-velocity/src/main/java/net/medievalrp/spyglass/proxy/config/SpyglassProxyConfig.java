package net.medievalrp.spyglass.proxy.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.medievalrp.spyglass.api.util.Duration;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

/**
 * HOCON config for the proxy plugin. Mirrors the {@code database} block
 * of the Paper plugin's {@code config.conf} so an operator can copy the
 * connection details verbatim. The proxy never records, so retention,
 * limits, defaults, and event toggles are all absent here.
 */
public record SpyglassProxyConfig(
        Database database,
        Defaults defaults,
        Limits limits) {

    public static SpyglassProxyConfig load(Path dataDirectory) throws IOException {
        Files.createDirectories(dataDirectory);
        Path path = dataDirectory.resolve("config.conf");
        if (Files.notExists(path)) {
            // First-boot stub. Operators copy the database block from
            // their Paper config.conf into this file.
            Files.writeString(path, defaultConfig());
        }

        HoconConfigurationLoader loader = HoconConfigurationLoader.builder()
                .path(path)
                .build();
        ConfigurationNode root = loader.load();

        String backendName = root.node("database", "backend").getString("mongo").trim().toLowerCase(java.util.Locale.ROOT);
        Backend backend = switch (backendName) {
            case "clickhouse" -> Backend.CLICKHOUSE;
            case "sqlite" -> Backend.SQLITE;
            case "mongo", "mongodb" -> Backend.MONGO;
            case "mariadb", "mysql", "maria" -> Backend.MARIADB;
            default -> throw new IOException("Unknown database.backend: " + backendName
                    + " (expected 'sqlite', 'mongo', 'clickhouse', or 'mariadb')");
        };

        return new SpyglassProxyConfig(
                new Database(
                        backend,
                        root.node("database", "uri").getString("mongodb://localhost:27017"),
                        root.node("database", "name").getString("Spyglass"),
                        root.node("database", "collection").getString("EventRecords"),
                        new ClickHouse(
                                root.node("database", "clickhouse", "host").getString("localhost"),
                                root.node("database", "clickhouse", "port").getInt(8123),
                                root.node("database", "clickhouse", "database").getString("spyglass"),
                                root.node("database", "clickhouse", "table").getString("event_records"),
                                root.node("database", "clickhouse", "user").getString("default"),
                                root.node("database", "clickhouse", "password").getString(""),
                                root.node("database", "clickhouse", "ssl").getBoolean(false)),
                        new Sqlite(
                                root.node("database", "sqlite", "path").getString("spyglass.db")),
                        new MariaDb(
                                root.node("database", "mariadb", "host").getString("localhost"),
                                root.node("database", "mariadb", "port").getInt(3306),
                                root.node("database", "mariadb", "database").getString("spyglass"),
                                root.node("database", "mariadb", "user").getString("root"),
                                root.node("database", "mariadb", "password").getString(""),
                                root.node("database", "mariadb", "ssl").getBoolean(false))),
                new Defaults(
                        Duration.parse(root.node("defaults", "time").getString("4h"))),
                new Limits(
                        root.node("limits", "search-result").getInt(1_000),
                        root.node("limits", "page-size").getInt(8)));
    }

    public enum Backend {
        MONGO,
        CLICKHOUSE,
        SQLITE,
        MARIADB
    }

    public record Database(
            Backend backend,
            String uri,
            String name,
            String collection,
            ClickHouse clickhouse,
            Sqlite sqlite,
            MariaDb mariadb) {
    }

    /**
     * MariaDB / MySQL connection settings (used when
     * {@code backend = "mariadb"} / {@code "mysql"}). Served over the
     * network, so unlike the embedded SQLite file the proxy reaches it the
     * same way the Paper server does - it just opens it read-only.
     */
    public record MariaDb(
            String host,
            int port,
            String database,
            String user,
            String password,
            boolean ssl) {
    }

    /**
     * Embedded-SQLite location (used when {@code backend = "sqlite"}). The
     * proxy opens this file <b>read-only</b>; it must be reachable on the
     * proxy host's filesystem (same machine or a shared mount), since
     * unlike Mongo / ClickHouse an embedded SQLite file is not served over
     * the network.
     */
    public record Sqlite(String path) {
        public Sqlite {
            path = path == null || path.isBlank() ? "spyglass.db" : path.trim();
        }
    }

    public record ClickHouse(
            String host,
            int port,
            String database,
            String table,
            String user,
            String password,
            boolean ssl) {
    }

    /**
     * Default time window applied when no {@code t:} is on the command
     * line. Always enforced - there's no 0-disable for time, since an
     * unbounded query against a long-lived store can pull tens of
     * millions of rows.
     */
    public record Defaults(Duration time) {
    }

    /**
     * Search-side limits. The proxy never records, so retention / queue /
     * rollback caps don't apply here.
     */
    public record Limits(int searchResult, int pageSize) {
    }

    private static String defaultConfig() {
        return """
                # Spyglass proxy plugin - Velocity-side companion to the Paper
                # Spyglass plugin. Copy the database block from your Paper
                # config.conf so this proxy reads the same backend.
                database {
                  # "sqlite", "mongo", "clickhouse", or "mariadb" - must
                  # match the Paper-side backend.
                  backend = "mongo"

                  # Mongo (used when backend = "mongo")
                  uri = "mongodb://localhost:27017"
                  name = "Spyglass"
                  collection = "EventRecords"

                  # ClickHouse (used when backend = "clickhouse")
                  clickhouse {
                    host = "localhost"
                    port = 8123
                    database = "spyglass"
                    table = "event_records"
                    user = "default"
                    password = ""
                    ssl = false
                  }

                  # SQLite (used when backend = "sqlite"). The proxy opens
                  # this file READ-ONLY, so it must sit on the proxy host's
                  # filesystem (same machine as the Paper server, or a shared
                  # mount) - an embedded SQLite file is not served over the
                  # network the way Mongo / ClickHouse are.
                  sqlite {
                    path = "spyglass.db"
                  }

                  # MariaDB / MySQL (used when backend = "mariadb"). Served
                  # over the network, so the proxy reaches it the same way
                  # the Paper server does (read-only).
                  mariadb {
                    host = "localhost"
                    port = 3306
                    database = "spyglass"
                    user = "root"
                    password = ""
                    ssl = false
                  }
                }

                defaults {
                  # Default time window when t: is omitted. Always enforced;
                  # the proxy has no global view of player locations, so an
                  # unbounded /sgv search would pull every row in the store.
                  # Override per-query with t:1d / t:30m / etc.
                  time = "4h"
                }

                limits {
                  # Hard ceiling on rows pulled per search; pagination slices
                  # this client-side. Bump if cross-server queries routinely
                  # exceed the default.
                  search-result = 1000
                  page-size = 8
                }
                """;
    }
}
