package net.medievalrp.spyglass.plugin.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Validate the whole candidate before changing any running service. */
public final class LiveConfig {
    private LiveConfig() {}

    public static boolean recordsEnabled(SpyglassConfig config, net.medievalrp.spyglass.api.event.EventRecord record) {
        var origin = record.origin();
        if (origin != null && (origin.kind().equals("worldedit") || origin.kind().equals("fawe"))) {
            return config.worldedit().enabled();
        }
        return !config.events().containsKey(record.event()) || config.enabled(record.event());
    }

    public static void validateFile(java.nio.file.Path path) throws java.io.IOException {
        var root = org.spongepowered.configurate.hocon.HoconConfigurationLoader.builder().path(path).build().load();
        String material = root.node("tool", "material").getString();
        if (material != null && (org.bukkit.Material.matchMaterial(material) == null
                || !org.bukkit.Material.matchMaterial(material).isItem()
                || org.bukkit.Material.matchMaterial(material).isAir())) {
            throw new IllegalArgumentException("Invalid tool.material: " + material);
        }
        // Startup tolerates a malformed per-event override. Reload must reject
        // it rather than silently replacing a keep-forever rule with global expiry.
        for (var event : root.node("events").childrenMap().entrySet()) {
            String retention = event.getValue().node("retention").getString();
            if (retention != null && !retention.isBlank()) SpyglassConfig.parseGlobalRetention(retention);
        }
    }

    public static List<String> restartRequired(SpyglassConfig before, SpyglassConfig after) {
        List<String> changes = new ArrayList<>();
        compare(before, after, "", java.util.Set.of("events", "tool", "commandRedact", "storage"), changes);
        compare(before.storage(), after.storage(), "storage.", java.util.Set.of("retention"), changes);
        return List.copyOf(changes);
    }

    private static void compare(Record before, Record after, String prefix,
            java.util.Set<String> live, List<String> changes) {
        try {
            for (var field : before.getClass().getRecordComponents()) {
                if (!live.contains(field.getName()) && !Objects.equals(
                        field.getAccessor().invoke(before), field.getAccessor().invoke(after))) {
                    changes.add(prefix + field.getName());
                }
            }
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Cannot validate reload configuration", ex);
        }
    }
}
