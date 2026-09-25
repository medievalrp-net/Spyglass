package net.medievalrp.spyglass.plugin;

import java.io.IOException;
import java.util.Properties;

/** Compatibility contract embedded in each distribution, before InvUI is loaded. */
public final class MinecraftTarget {
    private static final Properties TARGET = load();

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

    public static String version() {
        return TARGET.getProperty("minecraft");
    }

    public static boolean supports(String version) {
        return version != null && (version.equals(version()) || version.startsWith(version() + "-"));
    }
}
