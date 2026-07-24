package net.medievalrp.spyglass.plugin.snapshot;

import java.util.logging.Logger;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

/**
 * Decides whether this server gets the InvUI {@code /sg snapshot} GUI. Same
 * split as {@code SalvageViews}: the bundled InvUI (1.49) is the last
 * multi-version line and supports Minecraft 1.x (up to 1.21.x) only; 26.x
 * needs InvUI 2 (Java 25), which we do not ship. On unsupported versions
 * there is <b>no GUI</b> - the caller falls back to a text listing instead,
 * deliberately, so no unverified inventory-click surface ships on a version
 * we cannot validate it against.
 *
 * <p>This gate is also what keeps a 26.x server from loading any InvUI
 * class: {@link InvUiSnapshotView} is referenced only inside the supported
 * branch below, so its classes are never resolved on the fallback path.
 */
public final class SnapshotViews {

    private SnapshotViews() {
    }

    /**
     * The InvUI GUI view for this server, or {@code null} on versions InvUI
     * does not support (the caller then serves {@code /sg snapshot} through
     * the text-fallback listing only).
     *
     * @param bukkitVersion {@code Bukkit.getBukkitVersion()}, e.g.
     *                      {@code "1.21.11-R0.1-SNAPSHOT"} or {@code "26.1.2-R0.1-SNAPSHOT"}
     * @param takes         the shared take engine (permission, whole-stack fit
     *                      rule, audit) - the same instance the text-fallback
     *                      command uses, so the two surfaces cannot drift
     */
    @Nullable
    public static SnapshotView guiOrNull(Plugin plugin, String bukkitVersion,
                                          SnapshotTakes takes, Logger logger) {
        if (!invUiSupported(bukkitVersion)) {
            return null;
        }
        // Referenced only here: on an unsupported server this line never runs,
        // so no InvUI class is resolved/loaded.
        return new InvUiSnapshotView(plugin, takes, logger);
    }

    /** True if the bundled InvUI 1.49 supports this Minecraft version (major == 1). */
    public static boolean invUiSupported(String bukkitVersion) {
        if (bukkitVersion == null || bukkitVersion.isEmpty()) {
            return false;
        }
        // Strip the "-R0.1-SNAPSHOT" suffix, then read the leading major number.
        // InvUI 1.49 covers 1.14 - 1.21.x; the post-1.21 scheme starts at "26.x",
        // which it does not support.
        String v = bukkitVersion;
        int dash = v.indexOf('-');
        if (dash > 0) {
            v = v.substring(0, dash);
        }
        int dot = v.indexOf('.');
        String major = dot > 0 ? v.substring(0, dot) : v;
        try {
            return Integer.parseInt(major) == 1;
        } catch (NumberFormatException ex) {
            return false;
        }
    }
}
