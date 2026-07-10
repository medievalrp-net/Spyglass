package net.medievalrp.spyglass.plugin.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import org.jetbrains.annotations.ApiStatus;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.serialize.SerializationException;

/**
 * Upgrades an on-disk config to the current schema version without losing the
 * operator's settings.
 *
 * <p>Configs predating the {@code config-version} key read as version 1. When
 * the on-disk version is older than {@link SpyglassConfig#CONFIG_VERSION} the
 * loader backs up the file (see {@link #backup}) and then rebuilds it with
 * {@link #migrate}: the bundled template supplies the structure, comments, and
 * new defaults, and the operator's values are overlaid on top. Moved keys are
 * relocated via a remap table; everything else lands at the same path. Keys the
 * template no longer has are dropped, so removed settings don't linger.
 *
 * <p>Rebuilding from the template (rather than editing the old file in place) is
 * what lets an upgraded config pick up the current comments - the comments live
 * on the template node, not on the operator's old file.
 */
@ApiStatus.Internal
public final class ConfigMigrator {

    private ConfigMigrator() {
    }

    /** The on-disk schema version; absent (pre-versioning) reads as 1. */
    public static int readVersion(ConfigurationNode root) {
        return root.node("config-version").getInt(1);
    }

    /**
     * Copies {@code configFile} to a sibling {@code <name>.<label>.bak}, adding a
     * numeric suffix rather than overwriting an existing backup, and returns the
     * path written. Called before any migration touches the original, so a failed
     * or half-written migration always leaves the operator's file recoverable.
     */
    public static Path backup(Path configFile, String label) throws IOException {
        Path dir = configFile.getParent();
        String base = configFile.getFileName() + "." + label + ".bak";
        Path target = dir.resolve(base);
        int suffix = 1;
        while (Files.exists(target)) {
            target = dir.resolve(base + "." + suffix++);
        }
        Files.copy(configFile, target, StandardCopyOption.COPY_ATTRIBUTES);
        return target;
    }

    /**
     * Overlays the operator's values from {@code user} onto {@code template} and
     * stamps {@code toVersion}. {@code remaps} maps an old dotted key path to its
     * new location; a null target drops the key. Lists and scalars are copied
     * whole (so a list value replaces the default rather than index-merging with
     * it); maps are recursed. Mutates and returns {@code template}.
     */
    public static ConfigurationNode migrate(ConfigurationNode user, ConfigurationNode template,
                                            Map<String, String> remaps, int toVersion)
            throws SerializationException {
        overlay(user, template, remaps, new ArrayDeque<>());
        template.node("config-version").set(toVersion);
        return template;
    }

    private static void overlay(ConfigurationNode node, ConfigurationNode template,
                                Map<String, String> remaps, Deque<Object> path)
            throws SerializationException {
        if (node.isMap()) {
            for (Map.Entry<Object, ? extends ConfigurationNode> child : node.childrenMap().entrySet()) {
                path.addLast(child.getKey());
                overlay(child.getValue(), template, remaps, path);
                path.removeLast();
            }
            return;
        }
        // Scalar or list leaf. raw() hands back the whole value (a List stays a
        // List), so setting it replaces the template default outright.
        StringBuilder dotted = new StringBuilder();
        for (Object key : path) {
            if (dotted.length() > 0) {
                dotted.append('.');
            }
            dotted.append(key);
        }
        if (remaps.containsKey(dotted.toString())) {
            String target = remaps.get(dotted.toString());
            if (target == null) {
                return;
            }
            template.node((Object[]) target.split("\\.")).set(node.raw());
        } else {
            template.node(path.toArray()).set(node.raw());
        }
    }
}
