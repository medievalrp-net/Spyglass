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
        java.util.List<String> commandRedact,
        Tool tool,
        Server server,
        Metrics metrics,
        Analytics analytics) {

    /**
     * Default heads for {@code events.command.redact} (#47): the common
     * auth-plugin command set whose arguments are credentials. Applied
     * when the key is absent from an existing on-disk config so old
     * installs pick up redaction on upgrade; an explicit
     * {@code redact = []} opts out.
     */
    public static final java.util.List<String> DEFAULT_COMMAND_REDACT = java.util.List.of(
            "login", "register", "l", "reg", "changepassword", "changepw",
            "unregister", "premium", "2fa", "totp", "auth");

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
                        value.node("past-tense").getString(String.valueOf(key)),
                        readEventRetention(value, String.valueOf(key), plugin.getLogger()))));

        java.util.List<String> commandRedact = parseCommandRedact(root);

        String backendName = root.node("database", "backend").getString("sqlite").trim().toLowerCase(java.util.Locale.ROOT);
        Backend backend = switch (backendName) {
            case "clickhouse" -> Backend.CLICKHOUSE;
            case "sqlite" -> Backend.SQLITE;
            case "mongo", "mongodb" -> Backend.MONGO;
            case "mariadb", "mysql", "maria" -> Backend.MARIADB;
            default -> throw new IOException("Unknown database.backend: " + backendName
                    + " (expected 'sqlite', 'mongo', 'clickhouse', or 'mariadb')");
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
                new Storage(
                        Duration.parse(root.node("storage", "retention").getString("26w")),
                        root.node("storage", "queue-capacity").getInt(100_000),
                        root.node("storage", "queue-max").getInt(500_000),
                        root.node("storage", "spill-to-disk").getBoolean(true),
                        root.node("storage", "spill-drain-rate").getInt(20_000),
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
                        root.node("limits", "chat-dump").getInt(50),
                        root.node("limits", "rollback-batch-size").getInt(4_000),
                        Duration.parse(root.node("limits", "rollback-flush-timeout").getString("30s")),
                        // Tick-budget cap for the per-tick world-write phase.
                        // 50 ms = one full tick. Default 15 ms (~30% of a
                        // tick) keeps server TPS at or near 20 even during
                        // a multi-million-block rollback. Operators on
                        // dedicated rollback windows can raise it for
                        // faster wall-clock at the cost of TPS.
                        root.node("limits", "rollback-tick-budget-ms").getLong(15L)),
                Map.copyOf(events),
                commandRedact,
                new Tool(Material.matchMaterial(root.node("tool", "material").getString("REDSTONE_LAMP"), false)),
                new Server(root.node("server", "name").getString("default")),
                parseMetrics(root),
                parseAnalytics(root));
    }

    /**
     * Absent key (configs predating #47) → the default auth set; an
     * explicit empty list is the operator's record-everything opt-out,
     * so it must NOT fall back to the default. Null elements (HOCON
     * oddities) are dropped rather than failing plugin enable.
     */
    static java.util.List<String> parseCommandRedact(ConfigurationNode root)
            throws org.spongepowered.configurate.serialize.SerializationException {
        ConfigurationNode redactNode = root.node("events", "command", "redact");
        if (redactNode.virtual()) {
            return DEFAULT_COMMAND_REDACT;
        }
        return redactNode.getList(String.class, java.util.List.of()).stream()
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    /**
     * bStats metrics opt-out. The key is absent on configs predating this
     * option (and on a fresh default that leaves it on), so a missing value
     * defaults to {@code true} - matching bStats' documented opt-out model.
     * An explicit {@code metrics.enabled = false} disables Spyglass's own
     * submission; the server-global {@code plugins/bStats/config.yml} the
     * library writes is a second, server-wide switch. Static + package-visible
     * so it's unit-testable headless, like {@link #parseCommandRedact}.
     */
    static Metrics parseMetrics(ConfigurationNode root) {
        return new Metrics(root.node("metrics", "enabled").getBoolean(true));
    }

    /**
     * Ingest analytics mode (#168). Opt-in: {@code analytics.enabled} defaults
     * to {@code false} (absent key = off), so a default config carries zero
     * counting overhead. {@code analytics.interval} is how often the console
     * report fires; it defaults to 60s and is floored at 5s if someone sets it
     * absurdly low. Static + package-visible so it's unit-testable headless,
     * like {@link #parseMetrics}.
     */
    static Analytics parseAnalytics(ConfigurationNode root) {
        boolean enabled = root.node("analytics", "enabled").getBoolean(false);
        Duration interval;
        try {
            interval = Duration.parse(root.node("analytics", "interval").getString("60s"));
        } catch (RuntimeException malformed) {
            interval = Duration.parse("60s");
        }
        if (interval.seconds() < 5L) {
            interval = Duration.parse("5s");
        }
        return new Analytics(enabled, interval);
    }

    public boolean enabled(String eventName) {
        return events.getOrDefault(eventName, new EventSettings(false, eventName, null)).enabled();
    }

    public String pastTense(String eventName) {
        EventSettings settings = events.get(eventName);
        if (settings != null) {
            return settings.pastTense();
        }
        // Custom events registered at runtime via SpyglassApi#registerEvent
        // aren't in config; their verb lives in the EventCatalog registry.
        String custom = net.medievalrp.spyglass.api.event.EventCatalog.pastTenseOf(eventName);
        return custom != null ? custom : eventName;
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
     *
     * <p>{@link #SQLITE} is the zero-ops option: every store the plugin
     * needs (records, undo ledger, wand state, salvage) lives in one
     * embedded SQLite file ({@code database.sqlite.path}), so a small or
     * medium server runs Spyglass with no external database process at
     * all.
     *
     * <p>{@link #MARIADB} is the client-server SQL option for operators
     * who already run a MariaDB or MySQL server (the common shared-host /
     * panel setup). Like ClickHouse and SQLite it is fully self-contained -
     * the records, undo ledger, wand state, and salvage all live in that
     * one database. The config name {@code "mariadb"}, {@code "mysql"}, and
     * {@code "maria"} all select it; one driver speaks both protocols.
     */
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
     * {@code backend = "mariadb"} / {@code "mysql"}). The database is
     * created on first start if the configured user has rights to;
     * otherwise pre-create it and point a least-privilege user here.
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
     * Embedded-SQLite settings (used when {@code backend = "sqlite"}).
     *
     * @param path location of the SQLite database file. A relative path
     *     is resolved against the plugin data folder, so the default
     *     {@code "spyglass.db"} lands at {@code plugins/Spyglass/spyglass.db}.
     *     The {@code -wal} / {@code -shm} sidecar files SQLite creates in
     *     WAL mode live alongside it.
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
     * @param queueMax      <b>Hard ceiling</b> on the ingest queue. When the
     *                      queue reaches this depth the off-main bulk-edit
     *                      firehoses (WorldEdit / FAWE) backpressure — they
     *                      block until the drain frees space — so a huge paste
     *                      can't grow the queue into an OutOfMemoryError. The
     *                      main thread is never blocked. {@code 0} restores the
     *                      legacy unbounded queue. Must sit above
     *                      {@code queueCapacity} so the warning precedes it.
     * @param spillToDisk   when {@code true} (default), the bulk-edit firehose
     *                      writes its overflow to an on-disk spill buffer once
     *                      the queue hits {@code queueMax}, instead of holding
     *                      it in RAM. This is what keeps an uncappable vanilla
     *                      WorldEdit paste heap-flat without dropping records:
     *                      the drain replays spilled segments. Requires
     *                      {@code queueMax > 0}; needs a fast local disk.
     * @param spillDrainRate cap, in records/sec, on how fast the drain reclaims
     *                      the on-disk spill backlog in the background (#180). The
     *                      drain replays spilled segments using spare capacity (it
     *                      keeps live events fresh and pauses while the queue is at
     *                      the ceiling) but no faster than this, so reclaiming a
     *                      large backlog never saturates the store on a live
     *                      server. {@code 0} = unlimited (drain as fast as the
     *                      store accepts); raise it or set 0 during a maintenance
     *                      window to recover a big backlog faster.
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
    public record Storage(Duration retention, int queueCapacity, int queueMax,
                          boolean spillToDisk, int spillDrainRate, Duration flushTimeout,
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

    /**
     * Read an event node's {@code retention} value and parse it (#181, #185).
     *
     * <p>The value is fetched with the no-arg {@link ConfigurationNode#getString()}.
     * Configurate's {@code getString(String)} overload runs {@code requireNonNull}
     * on the supplied default, so {@code getString(null)} threw a
     * {@code NullPointerException} ("Failed to load config: def") on every config
     * whose events omit {@code retention} - which is the bundled default for all of
     * them, so 1.0.5 failed to enable everywhere. The nullable read returns
     * {@code null} for a missing key, which {@link #parseEventRetention} treats as
     * inherit-the-global. Kept package-visible so the absent-key path is unit-tested
     * without standing up a full plugin.
     */
    static Long readEventRetention(ConfigurationNode eventNode, String event,
            java.util.logging.Logger logger) {
        return parseEventRetention(eventNode.node("retention").getString(), event, logger);
    }

    /**
     * Parse a per-event {@code events.<name>.retention} value (#181) into seconds.
     * {@code null}/blank -> inherit the global default; {@code "0"}/{@code "never"}/
     * {@code "forever"}/{@code "off"} -> keep forever; otherwise a duration like
     * {@code "12w"}. An unparseable value warns and falls back to the global default.
     */
    static Long parseEventRetention(String raw, String event, java.util.logging.Logger logger) {
        if (raw == null) {
            return null; // inherit storage.retention
        }
        String v = raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (v.isEmpty()) {
            return null;
        }
        if (v.equals("0") || v.equals("never") || v.equals("forever") || v.equals("off")) {
            return net.medievalrp.spyglass.plugin.storage.RetentionPolicy.NEVER_SECONDS;
        }
        try {
            return Duration.parse(raw).seconds();
        } catch (RuntimeException ex) {
            logger.warning("Spyglass: invalid retention \"" + raw + "\" for event '"
                    + event + "'; inheriting the global storage.retention. (" + ex.getMessage() + ")");
            return null;
        }
    }

    /**
     * Per-event retention policy (#181): the global {@code storage.retention} as
     * the default, with overrides for every event that set its own
     * {@code retention}. Consumed by the active {@code RecordStore}.
     */
    public net.medievalrp.spyglass.plugin.storage.RetentionPolicy retentionPolicy() {
        Map<String, Long> overrides = new LinkedHashMap<>();
        events.forEach((name, settings) -> {
            if (settings.retentionSeconds() != null) {
                overrides.put(name, settings.retentionSeconds());
            }
        });
        return new net.medievalrp.spyglass.plugin.storage.RetentionPolicy(
                storage.retention().seconds(), overrides);
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

    public record Limits(int maxRadius, int searchResult, int chatDump,
                         int rollbackBatchSize, Duration rollbackFlushTimeout,
                         long rollbackTickBudgetMs) {
    }

    /**
     * @param retentionSeconds per-event retention override in seconds (#181), or
     *                         {@code null} to inherit the global
     *                         {@code storage.retention}. {@link
     *                         net.medievalrp.spyglass.plugin.storage.RetentionPolicy#NEVER_SECONDS}
     *                         marks a "keep forever" type.
     */
    public record EventSettings(boolean enabled, String pastTense, Long retentionSeconds) {
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

    /**
     * Anonymous usage metrics via bStats (https://bstats.org). {@code enabled}
     * defaults to {@code true}; set {@code metrics.enabled = false} in
     * {@code config.conf} to opt this server out of Spyglass's submission.
     */
    public record Metrics(boolean enabled) {
    }

    /**
     * Ingest analytics mode (#168). {@code enabled} defaults to {@code false}
     * (opt-in, zero overhead off); {@code interval} is the console report
     * period (default 60s, floored at 5s). When on, Spyglass tallies recorded
     * events per second by type plus pipeline load, logs a periodic report, and
     * answers {@code /spyglass stats}.
     */
    public record Analytics(boolean enabled, Duration interval) {
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
