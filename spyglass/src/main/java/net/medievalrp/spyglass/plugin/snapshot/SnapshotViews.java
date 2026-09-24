package net.medievalrp.spyglass.plugin.snapshot;

import java.util.logging.Logger;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

/** Selects the bundled InvUI GUI on its pinned Minecraft release. */
public final class SnapshotViews {

    private SnapshotViews() {
    }

    /**
     * The InvUI GUI view for this server, or {@code null} on versions InvUI
     * does not support (the caller then serves {@code /sg snapshot} through
     * the text-fallback listing only).
     *
     * @param minecraftVersion {@code Bukkit.getMinecraftVersion()}, e.g.
     *                      {@code "26.3"}
     * @param takes         the shared take engine (permission, whole-stack fit
     *                      rule, audit) - the same instance the text-fallback
     *                      command uses, so the two surfaces cannot drift
     */
    @Nullable
    public static SnapshotView guiOrNull(Plugin plugin, String minecraftVersion,
                                          SnapshotTakes takes, SnapshotSessions sessions,
                                          Logger logger) {
        if (!invUiSupported(minecraftVersion)) {
            return null;
        }
        // Referenced only here: on an unsupported server this line never runs,
        // so no InvUI class is resolved/loaded.
        return new InvUiSnapshotView(plugin, takes, sessions, logger);
    }

    /** InvUI 2 is version-specific; do not load its internals on another release line. */
    public static boolean invUiSupported(String minecraftVersion) {
        return net.medievalrp.spyglass.plugin.MinecraftTarget.supports(minecraftVersion);
    }
}
