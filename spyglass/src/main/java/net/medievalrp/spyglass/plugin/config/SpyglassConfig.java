package net.medievalrp.spyglass.plugin.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import net.medievalrp.spyglass.api.util.Duration;
import org.bukkit.Material;
import org.bukkit.plugin.java.JavaPlugin;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

public record SpyglassConfig(
        Database database,
        Storage storage,
        Defaults defaults,
        Limits limits,
        Map<String, EventSettings> events,
        Tool tool,
        Server server) {

    public static SpyglassConfig load(JavaPlugin plugin) throws IOException {
        Path path = plugin.getDataFolder().toPath().resolve("config.conf");
        Files.createDirectories(path.getParent());
        if (Files.notExists(path)) {
            plugin.saveResource("config.conf", false);
        }

        HoconConfigurationLoader loader = HoconConfigurationLoader.builder()
                .path(path)
                .build();
        ConfigurationNode root = loader.load();

        // Auto-merge: any event present in the jar's default config.conf but missing from
        // the on-disk config gets added with defaults. Prevents silent "event not enabled"
        // when a new plugin version introduces a new event name.
        ConfigurationNode bundled = loadBundledDefaults(plugin);
        boolean changed = mergeMissingEvents(root, bundled);
        if (changed) {
            loader.save(root);
        }

        Map<String, EventSettings> events = new LinkedHashMap<>();
        root.node("events").childrenMap().forEach((key, value) -> events.put(
                String.valueOf(key),
                new EventSettings(
                        value.node("enabled").getBoolean(true),
                        value.node("past-tense").getString(String.valueOf(key)))));

        String backendName = root.node("database", "backend").getString("mongo").trim().toLowerCase(java.util.Locale.ROOT);
        Backend backend = switch (backendName) {
            case "clickhouse" -> Backend.CLICKHOUSE;
            case "mongo", "mongodb" -> Backend.MONGO;
            default -> throw new IOException("Unknown database.backend: " + backendName
                    + " (expected 'mongo' or 'clickhouse')");
        };
        return new SpyglassConfig(
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
                new Storage(
                        Duration.parse(root.node("storage", "retention").getString("4w")),
                        root.node("storage", "queue-capacity").getInt(100_000),
                        Duration.parse(root.node("storage", "flush-timeout").getString("5s")),
                        parseDurability(root.node("storage", "durability").getString("ram")),
                        "synthesized".equalsIgnoreCase(
                                root.node("storage", "rolled-audit").getString("synthesized"))),
                new Defaults(
                        root.node("defaults", "enabled").getBoolean(true),
                        root.node("defaults", "radius").getInt(250),
                        Duration.parse(root.node("defaults", "time").getString("4h"))),
                new Limits(
                        root.node("limits", "max-radius").getInt(250),
                        root.node("limits", "search-result").getInt(1_000),
                        root.node("limits", "rollback-result").getInt(10_000),
                        root.node("limits", "chat-dump").getInt(50),
                        root.node("limits", "rollback-batch-size").getInt(4_000),
                        Duration.parse(root.node("limits", "rollback-flush-timeout").getString("30s")),
                        root.node("limits", "rollback-page-size").getInt(20_000),
                        root.node("limits", "rollback-undo-cap").getInt(5_000_000),
                        // Tick-budget cap for the per-tick world-write phase.
                        // 50 ms = one full tick. Default 15 ms (~30% of a
                        // tick) keeps server TPS at or near 20 even during
                        // a multi-million-block rollback. Operators on
                        // dedicated rollback windows can raise it for
                        // faster wall-clock at the cost of TPS.
                        root.node("limits", "rollback-tick-budget-ms").getLong(15L)),
                Map.copyOf(events),
                new Tool(Material.matchMaterial(root.node("tool", "material").getString("REDSTONE_LAMP"), false)),
                new Server(root.node("server", "name").getString("default")));
    }

    public boolean enabled(String eventName) {
        return events.getOrDefault(eventName, new EventSettings(false, eventName)).enabled();
    }

    public String pastTense(String eventName) {
        return events.getOrDefault(eventName, new EventSettings(false, eventName)).pastTense();
    }

    /**
     * Pick of event-log backend.
     *
     * <p>{@link #MONGO} keeps the original Mongo-only deployment shape:
     * one MongoDB hosts the EventRecords collection plus the
     * UndoHistory and Tools auxiliary collections.
     *
     * <p>{@link #CLICKHOUSE} is fully self-contained — every collection
     * the plugin needs lives in the ClickHouse instance, including the
     * undo ledger and the wand-state ledger. Mongo is not required at
     * all when this backend is selected. Operators run one or the
     * other, never both.
     */
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
     * Storage-tier tuning.
     *
     * @param retention     how long records live in Mongo before the
     *                      TTL index drops them.
     * @param queueCapacity <b>Warn threshold, not a drop ceiling.</b> The
     *                      ingest queue is unbounded and never rejects at
     *                      intake — same contract as v1. Crossing this
     *                      depth logs a warning so operators notice Mongo
     *                      backlog early. Field name kept as
     *                      {@code queueCapacity} for config-file
     *                      back-compat; semantically it's the
     *                      warn threshold passed to
     *                      {@link net.medievalrp.spyglass.plugin.pipeline.AsyncRecorder}.
     * @param flushTimeout  upper bound on how long {@code onDisable} will
     *                      wait for the queue to drain before returning.
     * @param durability    how aggressive the write path is about
     *                      surviving hard JVM crashes. {@link
     *                      Durability#RAM} (default) keeps the in-flight
     *                      queue purely in memory — fast, but anything
     *                      between event-fired and DB-acked is lost on
     *                      power-cut / OOM / SIGKILL. {@link
     *                      Durability#WAL_BATCHED} writes each drain
     *                      batch to an append-only file with one
     *                      {@code fsync} before pushing to the database
     *                      and deletes it after the DB acks; on next
     *                      startup any leftover files are replayed.
     *                      One fsync amortised over a 512-row batch is
     *                      cheap; per-event overhead is negligible.
     */
    public record Storage(Duration retention, int queueCapacity, Duration flushTimeout,
                          Durability durability, boolean rolledAuditSynthesized) {
    }

    /**
     * Crash-recovery contract for the in-flight ingest queue.
     */
    public enum Durability {
        /**
         * In-RAM queue only. Hard JVM crash loses everything queued
         * but not yet pushed to the DB (typically the last ~250 ms
         * of events). Fastest option; fine for community servers.
         */
        RAM,
        /**
         * Append-only write-ahead log per drain batch with one
         * {@code fsync} before the DB push. Crash recovery replays
         * any pending files on next startup. Right choice for
         * servers that crash regularly or for compliance-style
         * audit logs.
         */
        WAL_BATCHED
    }

    private static Durability parseDurability(String raw) {
        String key = raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT).replace('-', '_');
        return switch (key) {
            case "wal_batched", "wal" -> Durability.WAL_BATCHED;
            case "ram", "" -> Durability.RAM;
            default -> throw new IllegalArgumentException(
                    "Unknown storage.durability: " + raw + " (expected 'ram' or 'wal-batched')");
        };
    }

    public record Defaults(boolean enabled, int radius, Duration time) {
    }

    public record Limits(int maxRadius, int searchResult, int rollbackResult, int chatDump,
                         int rollbackBatchSize, Duration rollbackFlushTimeout,
                         int rollbackPageSize, int rollbackUndoCap,
                         long rollbackTickBudgetMs) {
    }

    public record EventSettings(boolean enabled, String pastTense) {
    }

    public record Tool(Material material) {
        public Tool {
            material = material == null ? Material.REDSTONE_LAMP : material;
        }
    }

    /**
     * Identifier for this Spyglass instance, stamped onto every recorded
     * event so a shared backend can hold logs from many backend servers.
     * Configured under {@code server.name} in {@code config.conf}; defaults
     * to {@code "default"} for a single-server deployment.
     */
    public record Server(String name) {
        public Server {
            name = name == null ? "default" : name.trim();
            if (name.isEmpty()) {
                name = "default";
            }
        }
    }

    private static ConfigurationNode loadBundledDefaults(JavaPlugin plugin) throws IOException {
        try (java.io.InputStream stream = plugin.getResource("config.conf")) {
            if (stream == null) {
                return HoconConfigurationLoader.builder().build().createNode();
            }
            return HoconConfigurationLoader.builder()
                    .source(() -> new java.io.BufferedReader(new java.io.InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8)))
                    .build()
                    .load();
        }
    }

    private static boolean mergeMissingEvents(ConfigurationNode root, ConfigurationNode bundled) {
        ConfigurationNode rootEvents = root.node("events");
        ConfigurationNode bundledEvents = bundled.node("events");
        boolean changed = false;
        for (Map.Entry<Object, ? extends ConfigurationNode> entry : bundledEvents.childrenMap().entrySet()) {
            ConfigurationNode existing = rootEvents.node(entry.getKey());
            if (existing.virtual()) {
                try {
                    existing.set(entry.getValue().raw());
                } catch (org.spongepowered.configurate.serialize.SerializationException ex) {
                    throw new RuntimeException(ex);
                }
                changed = true;
            }
        }
        return changed;
    }
}
