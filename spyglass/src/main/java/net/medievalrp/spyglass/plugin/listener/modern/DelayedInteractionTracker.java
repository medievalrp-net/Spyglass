package net.medievalrp.spyglass.plugin.listener.modern;

import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class DelayedInteractionTracker {

    private final JavaPlugin plugin;

    public DelayedInteractionTracker(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Schedule a callback to fire `delayTicks` later on the main thread. The
     * callback receives the block state as it stands at that time and the
     * player, and is responsible for deciding whether anything changed.
     */
    public void scheduleAfter(int delayTicks, Player player, Location location,
                              Consumer<DelayedContext> callback) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Block current = location.getBlock();
            Material nowMaterial = current.getType();
            callback.accept(new DelayedContext(player, location, nowMaterial));
        }, delayTicks);
    }

    public record DelayedContext(Player player, Location location, Material currentMaterial) {
    }
}
