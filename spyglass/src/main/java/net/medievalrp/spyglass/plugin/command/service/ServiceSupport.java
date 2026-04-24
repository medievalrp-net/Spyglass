package net.medievalrp.spyglass.plugin.command.service;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.ApiStatus;

/**
 * Main-thread scheduler indirection. Services that complete async work need
 * to bounce back to the Bukkit main thread before touching Bukkit state;
 * tests swap in {@link #synchronous()} to skip the scheduler entirely.
 *
 * <p>Sender feedback helpers (error/info/warn) moved to
 * {@link net.medievalrp.spyglass.plugin.command.render.Feedback}.
 */
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
}
