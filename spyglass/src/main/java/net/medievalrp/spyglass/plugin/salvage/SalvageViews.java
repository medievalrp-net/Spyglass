package net.medievalrp.spyglass.plugin.salvage;

import java.util.concurrent.Executor;
import java.util.logging.Logger;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

/** Selects the InvUI 2.5 GUI on its supported Minecraft 26.3 release line. */
public final class SalvageViews {

    private SalvageViews() {
    }

    /**
     * The InvUI GUI view for this server, or {@code null} on versions InvUI does
     * not support (the caller then serves salvage through the command path only).
     *
     * @param minecraftVersion {@code Bukkit.getMinecraftVersion()}, e.g.
     *                      {@code "26.3"}
     */
    @Nullable
    public static SalvageView guiOrNull(Plugin plugin, String minecraftVersion, SalvageStore store,
                                        Executor storeExecutor, Executor mainExecutor,
                                        SalvageWithdrawals withdrawals, int rollbackListLimit,
                                        Logger logger) {
        if (!invUiSupported(minecraftVersion)) {
            return null;
        }
        // Referenced only here: on an unsupported server this line never runs,
        // so no InvUI class is resolved/loaded.
        return new InvUiSalvageView(plugin, store, storeExecutor, mainExecutor,
                withdrawals, rollbackListLimit, logger);
    }

    /** InvUI 2 is version-specific; do not load its internals on another release line. */
    public static boolean invUiSupported(String minecraftVersion) {
        return minecraftVersion != null && minecraftVersion.matches("26\\.3(?:-.*)?");
    }
}
