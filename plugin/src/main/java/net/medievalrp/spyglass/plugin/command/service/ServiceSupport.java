package net.medievalrp.spyglass.plugin.command.service;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public interface ServiceSupport {

    void onMainThread(Runnable runnable);

    static ServiceSupport bukkit(JavaPlugin plugin) {
        return runnable -> {
            if (Bukkit.isPrimaryThread()) {
                runnable.run();
                return;
            }
            Bukkit.getScheduler().runTask(plugin, runnable);
        };
    }

    static ServiceSupport synchronous() {
        return Runnable::run;
    }

    static Component errorMessage(String message) {
        return Component.text(message, NamedTextColor.RED);
    }

    static Component infoMessage(String message) {
        return Component.text(message, NamedTextColor.DARK_GRAY);
    }

    static Component warnMessage(String message) {
        return Component.text(message, NamedTextColor.YELLOW);
    }
}
