package net.medievalrp.spyglass.plugin.integration;

import java.util.Map;
import java.util.logging.Level;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Loads the optional Crusalis platform integration — and, far more often, does not.
 *
 * <p>Spyglass is used by networks that have never heard of Crusalis, so the integration
 * module must be inert for them: not merely disabled at runtime, but never loaded. That
 * is what this class is for. It is the only place in the plugin that names the module,
 * it names it as a string, and it checks for the SDK's API with {@link Class#forName}
 * before touching anything. The sequence matters:
 *
 * <ol>
 *   <li>Config off → return, having loaded nothing.</li>
 *   <li>SDK API absent → one INFO line, having loaded nothing. This is the case that
 *       would otherwise be a {@code NoClassDefFoundError} on a server that enabled the
 *       integration without installing the SDK.</li>
 *   <li>Both present → load {@code PlatformIntegration} and call its static
 *       {@code start}. Its signature mentions no Crusalis type for exactly this
 *       reason, so resolving the method cannot itself fail on a missing class.</li>
 * </ol>
 *
 * <p>Nothing here throws. An integration that cannot start is an integration that does
 * not start; Spyglass's own storage is the record of record and must not be affected by
 * a copy of it failing.
 */
public final class CrusalisPlatformLoader {

    /** The API class whose presence means the SDK plugin is installed. */
    private static final String SDK_API = "net.crusalis.platform.sdk.api.Platform";

    /** The integration's entry point, in the optional module. */
    private static final String INTEGRATION =
            "net.medievalrp.spyglass.platform.PlatformIntegration";

    private CrusalisPlatformLoader() {
    }

    /**
     * Starts the integration if it is both wanted and possible.
     *
     * @param settings the {@code integrations.crusalis-platform} block as a map, from
     *     {@code SpyglassConfig.CrusalisPlatform#asMap()}
     * @return a handle to close on disable, or null when nothing was started
     */
    public static AutoCloseable start(JavaPlugin plugin, Map<String, Object> settings) {
        if (settings == null || !Boolean.TRUE.equals(settings.get("enabled"))) {
            return null;
        }
        ClassLoader loader = CrusalisPlatformLoader.class.getClassLoader();
        try {
            // initialize = false: presence is the question, and running a static
            // initializer to answer it would be a side effect on a class that may turn
            // out to be the wrong version anyway.
            Class.forName(SDK_API, false, loader);
        } catch (ClassNotFoundException | LinkageError e) {
            plugin.getLogger().info("integrations.crusalis-platform is enabled but the"
                    + " CrusalisPlatformSDK plugin is not installed; no events will be"
                    + " published. Spyglass's own logging is unaffected.");
            return null;
        }
        try {
            Class<?> integration = Class.forName(INTEGRATION, true, loader);
            Object handle = integration
                    .getMethod("start", JavaPlugin.class, Map.class)
                    .invoke(null, plugin, settings);
            return (AutoCloseable) handle;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "The Crusalis platform integration could"
                    + " not start; Spyglass continues without it.", e);
            return null;
        }
    }
}
