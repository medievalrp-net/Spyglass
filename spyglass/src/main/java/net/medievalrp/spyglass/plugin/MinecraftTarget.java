package net.medievalrp.spyglass.plugin;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/** Compatibility contract embedded in each distribution, checked before the plugin enables. */
public final class MinecraftTarget {
    private static final Properties TARGET = load();
    private static final List<String> SUPPORTED = List.copyOf(Arrays.stream(
                    TARGET.getProperty("supported", "").split(","))
            .map(String::trim)
            .filter(version -> !version.isEmpty())
            .toList());

    private MinecraftTarget() {}

    private static Properties load() {
        var properties = new Properties();
        try (var stream = MinecraftTarget.class.getResourceAsStream("/spyglass-target.properties")) {
            if (stream == null) throw new IllegalStateException("Missing Minecraft build target");
            properties.load(stream);
        } catch (IOException ex) {
            throw new ExceptionInInitializerError(ex);
        }
        return properties;
    }

    /** The Minecraft release this build compiled against (and is named after). */
    public static String version() {
        return TARGET.getProperty("minecraft");
    }

    /** Every Minecraft release this build runs on: its target and the newer supported ones. */
    public static List<String> supported() {
        return SUPPORTED;
    }

    public static boolean supports(String version) {
        if (version == null) return false;
        for (String supported : SUPPORTED) {
            if (version.equals(supported) || version.startsWith(supported + "-")) return true;
        }
        return false;
    }
}
