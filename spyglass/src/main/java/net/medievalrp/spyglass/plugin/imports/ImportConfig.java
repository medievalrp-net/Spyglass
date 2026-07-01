package net.medievalrp.spyglass.plugin.imports;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

/**
 * Loads {@code import.conf}, the credentials file for live-MySQL CoreProtect
 * imports. Deliberately separate from {@code config.conf}'s {@code database.*}
 * — these are read-only source credentials, never the Spyglass storage DB.
 */
public record ImportConfig(Map<String, MysqlSourceSpec> sources) {

    /** A named MySQL CoreProtect source. Blank serverName = use this server's name. */
    public record MysqlSourceSpec(String host, int port, String database,
                                  String user, String password, String serverName) {
    }

    public Optional<MysqlSourceSpec> source(String name) {
        return Optional.ofNullable(sources.get(name));
    }

    public static ImportConfig loadFrom(Path file) throws IOException {
        if (Files.notExists(file)) {
            return new ImportConfig(Map.of());
        }
        HoconConfigurationLoader loader = HoconConfigurationLoader.builder().path(file).build();
        ConfigurationNode root = loader.load();
        Map<String, MysqlSourceSpec> sources = new LinkedHashMap<>();
        root.node("sources").childrenMap().forEach((key, node) -> sources.put(
                String.valueOf(key),
                new MysqlSourceSpec(
                        node.node("host").getString(""),
                        node.node("port").getInt(3306),
                        node.node("database").getString(""),
                        node.node("user").getString(""),
                        node.node("password").getString(""),
                        node.node("server-name").getString(""))));
        return new ImportConfig(Map.copyOf(sources));
    }
}
