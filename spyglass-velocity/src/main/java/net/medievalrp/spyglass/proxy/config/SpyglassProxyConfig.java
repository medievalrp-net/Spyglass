package net.medievalrp.spyglass.proxy.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

        String backendName = root.node("database", "backend").getString("mongo").trim().toLowerCase();
        Backend backend = switch (backendName) {
            case "clickhouse" -> Backend.CLICKHOUSE;
            case "mongo", "mongodb" -> Backend.MONGO;
            default -> throw new IOException("Unknown database.backend: " + backendName
                    + " (expected 'mongo' or 'clickhouse')");
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
                                root.node("database", "clickhouse", "ssl").getBoolean(false))),
                new Limits(
                        root.node("limits", "search-result").getInt(1_000),
                        root.node("limits", "page-size").getInt(8)));
    }

    public enum Backend {
        MONGO,
        CLICKHOUSE
    }

    public record Database(
            Backend backend,
            String uri,
            String name,
            String collection,
            ClickHouse clickhouse) {
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
                  # "mongo" or "clickhouse" - must match the Paper-side backend.
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
