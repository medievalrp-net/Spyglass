package net.medievalrp.spyglass.plugin.salvage;

import java.util.concurrent.Executor;
import java.util.logging.Logger;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

/**
 * Decides whether this server gets the InvUI salvage GUI.
 *
 * <p>The bundled InvUI (1.49) is the last multi-version line and supports
 * Minecraft 1.x (up to 1.21.x) only; 26.x needs InvUI 2 (Java 25), which we do
 * not ship. On unsupported versions there is <b>no GUI</b> - salvage is
 * command-only ({@code SalvageService}), deliberately, so no unverified
 * inventory-click surface (a dupe risk we cannot validate on 26.x) is shipped.
 *
 * <p>This gate is also what keeps a 26.x server from loading any InvUI class:
 * {@link InvUiSalvageView} is referenced only inside the supported branch, so
 * its classes are never resolved on the fallback path.
 */
public final class SalvageViews {

    private SalvageViews() {
    }

    /**
     * The InvUI GUI view for this server, or {@code null} on versions InvUI does
     * not support (the caller then serves salvage through the command path only).
     *
     * @param bukkitVersion {@code Bukkit.getBukkitVersion()}, e.g.
     *                      {@code "1.21.11-R0.1-SNAPSHOT"} or {@code "26.1.2-R0.1-SNAPSHOT"}
     */
    @Nullable
    public static SalvageView guiOrNull(Plugin plugin, String bukkitVersion, SalvageStore store,
                                        Executor storeExecutor, Executor mainExecutor,
                                        SalvageWithdrawals withdrawals, int rollbackListLimit,
                                        Logger logger) {
        if (!invUiSupported(bukkitVersion)) {
            return null;
        }
        // Referenced only here: on an unsupported server this line never runs,
        // so no InvUI class is resolved/loaded.
        return new InvUiSalvageView(plugin, store, storeExecutor, mainExecutor,
                withdrawals, rollbackListLimit, logger);
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
