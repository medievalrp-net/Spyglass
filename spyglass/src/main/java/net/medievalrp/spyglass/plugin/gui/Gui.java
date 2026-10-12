package net.medievalrp.spyglass.plugin.gui;

import me.devnatan.inventoryframework.View;
import me.devnatan.inventoryframework.ViewFrame;
import me.devnatan.inventoryframework.Viewer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Spyglass's inventory menus, on DevNatan's inventory-framework. One frame
 * serves the whole plugin; every menu is a {@link Menu} or {@link PagedMenu}
 * opened through it.
 *
 * <p>Both views cancel every interaction while open, so a menu is extract-only:
 * cell actions run, items never move. Menus are recognised by the player
 * viewing them, never by inventory title, so a renamed container cannot pass
 * for one.
 *
 * <p>Call everything on the main thread, and never from inside a click
 * action: Bukkit forbids opening an inventory during its click event, so a
 * click action that switches menus defers through {@link #nextTick}.
 */
public final class Gui {

    private final Plugin plugin;
    private final ViewFrame frame;

    public Gui(Plugin plugin) {
        this.plugin = plugin;
        // disableMetrics: Spyglass has its own opt-out bStats (metrics.enabled);
        // the library's separate reporting would ignore that switch.
        this.frame = ViewFrame.create(plugin)
                .with(new MenuView(), new PagedMenuView())
                .disableMetrics()
                .register();
    }

    public void open(Player player, Menu menu) {
        show(player, MenuView.class, menu);
    }

    public void open(Player player, PagedMenu menu) {
        show(player, PagedMenuView.class, menu);
    }

    /** Whether the player currently has a Spyglass menu open. */
    public boolean isViewing(Player player) {
        return frame.getViewer(player) != null;
    }

    /** Run {@code task} on the main thread next tick (outside any click event). */
    public void nextTick(Runnable task) {
        plugin.getServer().getScheduler().runTask(plugin, task);
    }

    private void show(Player player, Class<? extends View> view, Object data) {
        if (!player.isOnline()) {
            return;
        }
        Viewer viewer = frame.getViewer(player);
        if (viewer == null) {
            frame.open(view, player, data);
        } else {
            // ViewFrame#open ignores a player who already has a menu open;
            // switching from the open one keeps the library's viewer tracking
            // (and so its click cancelling) continuous across the change.
            viewer.getActiveContext().openForPlayer(view, data);
        }
    }
}
