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
        Tool tool) {

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

        return new SpyglassConfig(
                new Database(
                        root.node("database", "uri").getString("mongodb://localhost:27017"),
                        root.node("database", "name").getString("Spyglass"),
                        root.node("database", "collection").getString("EventRecords")),
                new Storage(
                        Duration.parse(root.node("storage", "retention").getString("4w")),
                        root.node("storage", "queue-capacity").getInt(100_000),
                        Duration.parse(root.node("storage", "flush-timeout").getString("5s"))),
                new Defaults(
                        root.node("defaults", "enabled").getBoolean(true),
                        root.node("defaults", "radius").getInt(5),
                        Duration.parse(root.node("defaults", "time").getString("3d"))),
                new Limits(
                        root.node("limits", "max-radius").getInt(250),
                        root.node("limits", "search-result").getInt(1_000),
                        root.node("limits", "rollback-result").getInt(10_000),
                        root.node("limits", "chat-dump").getInt(50)),
                Map.copyOf(events),
                new Tool(Material.matchMaterial(root.node("tool", "material").getString("REDSTONE_LAMP"), false)));
    }

    public boolean enabled(String eventName) {
        return events.getOrDefault(eventName, new EventSettings(false, eventName)).enabled();
    }

    public String pastTense(String eventName) {
        return events.getOrDefault(eventName, new EventSettings(false, eventName)).pastTense();
    }

    public record Database(String uri, String name, String collection) {
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
     */
    public record Storage(Duration retention, int queueCapacity, Duration flushTimeout) {
    }

    public record Defaults(boolean enabled, int radius, Duration time) {
    }

    public record Limits(int maxRadius, int searchResult, int rollbackResult, int chatDump) {
    }

    public record EventSettings(boolean enabled, String pastTense) {
    }

    public record Tool(Material material) {
        public Tool {
            material = material == null ? Material.REDSTONE_LAMP : material;
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
