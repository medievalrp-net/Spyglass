package net.medievalrp.spyglass.platform;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The {@code integrations.crusalis-platform} block, as plain JDK types.
 *
 * <p>Deliberately not a Configurate-aware type and deliberately not Spyglass's own
 * config record: this module is loaded reflectively by the plugin and must not share a
 * class with it beyond the API, so the plugin hands over a {@code Map} and this class
 * is where that map stops being untyped.
 */
public record PlatformSettings(
        boolean enabled,
        Set<String> include,
        Set<String> exclude,
        Set<String> redactCommands,
        Set<String> privateCommands,
        boolean publishRollbacks) {

    /** The config defaults, matching the shipped config.conf. */
    public static PlatformSettings defaults() {
        return new PlatformSettings(
                false,
                Set.of("*"),
                Set.of(),
                lower(Set.of("login", "l", "register", "reg", "changepassword", "cp", "2fa")),
                lower(Set.of("msg", "tell", "w", "whisper", "r", "reply", "m", "pm", "dm")),
                true);
    }

    /**
     * Reads the map the plugin passes in, falling back to {@link #defaults()} for
     * anything absent.
     *
     * <p>Unknown keys are ignored rather than rejected: this module ships inside a
     * Spyglass jar that may be older or newer than the config file beside it, and
     * refusing to start over a key from the other version would turn a config edit into
     * an outage of the whole integration.
     */
    @SuppressWarnings("unchecked")
    public static PlatformSettings from(Map<String, Object> raw) {
        PlatformSettings defaults = defaults();
        if (raw == null || raw.isEmpty()) {
            return defaults;
        }
        return new PlatformSettings(
                bool(raw.get("enabled"), defaults.enabled()),
                // include-actions: `include` is a HOCON reserved word, so the config
                // cannot spell it unquoted. Both are read — the plugin's own config
                // sends the former, and a caller that built the map from the Crusalis
                // spec may send the latter.
                strings(firstOf(raw, "include-actions", "include"), defaults.include()),
                strings(firstOf(raw, "exclude-actions", "exclude"), defaults.exclude()),
                strings(raw.get("redact-commands"), defaults.redactCommands()),
                strings(raw.get("private-commands"), defaults.privateCommands()),
                bool(raw.get("publish-rollbacks"), defaults.publishRollbacks()));
    }

    /**
     * Whether an action key passes the include/exclude filter.
     *
     * <p>Exclude wins, so {@code include: ["*"]} with an exclude list is the useful
     * shape: a new Spyglass action is published by default, because the alternative —
     * a new action silently missing from the store — is the failure nobody notices.
     */
    public boolean publishes(String actionKey) {
        if (actionKey == null) {
            return false;
        }
        String key = actionKey.toLowerCase(Locale.ROOT);
        if (exclude.contains(key)) {
            return false;
        }
        return include.contains("*") || include.contains(key);
    }

    private static Object firstOf(Map<String, Object> raw, String preferred, String fallback) {
        Object value = raw.get(preferred);
        return value != null ? value : raw.get(fallback);
    }

    private static boolean bool(Object value, boolean fallback) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s) {
            return Boolean.parseBoolean(s);
        }
        return fallback;
    }

    private static Set<String> strings(Object value, Set<String> fallback) {
        if (value instanceof Collection<?> collection) {
            Set<String> out = new LinkedHashSet<>();
            for (Object element : collection) {
                if (element != null) {
                    out.add(element.toString().toLowerCase(Locale.ROOT));
                }
            }
            return Set.copyOf(out);
        }
        return fallback;
    }

    private static Set<String> lower(Set<String> values) {
        Set<String> out = new LinkedHashSet<>();
        for (String value : values) {
            out.add(value.toLowerCase(Locale.ROOT));
        }
        return Set.copyOf(out);
    }
}
