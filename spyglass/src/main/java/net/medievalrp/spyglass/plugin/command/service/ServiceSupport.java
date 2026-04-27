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

    /**
     * Hand work off to an async pool. Used by services that finished
     * their main-thread side (e.g. block-placement during rollback) and
     * still owe a slow I/O step (writing the undo stack to ClickHouse)
     * that must NOT block the tick. Fire-and-forget; the runnable is
     * responsible for its own error logging.
     */
    void onAsyncThread(Runnable runnable);

    static ServiceSupport bukkit(JavaPlugin plugin) {
        return new ServiceSupport() {
            @Override
            public void onMainThread(Runnable runnable) {
                if (Bukkit.isPrimaryThread()) {
                    runnable.run();
                    return;
                }
                Bukkit.getScheduler().runTask(plugin, runnable);
            }

            @Override
            public void onAsyncThread(Runnable runnable) {
                Bukkit.getScheduler().runTaskAsynchronously(plugin, runnable);
            }
        };
    }

    static ServiceSupport synchronous() {
        return new ServiceSupport() {
            @Override
            public void onMainThread(Runnable runnable) {
                runnable.run();
            }

            @Override
            public void onAsyncThread(Runnable runnable) {
                runnable.run();
            }
        };
    }
}
