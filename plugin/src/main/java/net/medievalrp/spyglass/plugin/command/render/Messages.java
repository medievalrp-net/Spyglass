package net.medievalrp.spyglass.plugin.command.render;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.plugin.java.JavaPlugin;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class Messages {

    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final Map<String, String> values;

    private Messages(Map<String, String> values) {
        this.values = values;
    }

    public static Messages load(JavaPlugin plugin) throws IOException {
        Path path = plugin.getDataFolder().toPath().resolve("messages.conf");
        Files.createDirectories(path.getParent());
        if (Files.notExists(path)) {
            plugin.saveResource("messages.conf", false);
        }
        HoconConfigurationLoader loader = HoconConfigurationLoader.builder()
                .path(path)
                .build();
        ConfigurationNode root = loader.load();
        Map<String, String> values = new HashMap<>();
        flatten(root, "", values);
        return new Messages(values);
    }

    public Component component(String path, TagResolver... resolvers) {
        String template = values.getOrDefault(path, "<red>Missing message: " + path + "</red>");
        return miniMessage.deserialize(template, resolvers);
    }

    private static void flatten(ConfigurationNode node, String prefix, Map<String, String> values) {
        if (!node.virtual() && !node.isMap() && node.rawScalar() != null) {
            values.put(prefix, String.valueOf(node.rawScalar()));
            return;
        }
        node.childrenMap().forEach((key, child) -> {
            String childKey = prefix.isEmpty() ? String.valueOf(key) : prefix + "." + key;
            flatten(child, childKey, values);
        });
    }
}
