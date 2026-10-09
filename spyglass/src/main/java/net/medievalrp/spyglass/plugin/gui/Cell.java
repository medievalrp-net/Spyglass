package net.medievalrp.spyglass.plugin.gui;

import java.util.function.Consumer;
import me.devnatan.inventoryframework.component.BukkitItemComponentBuilder;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * One menu slot: the icon shown and what a click does. A {@code null} action
 * makes the cell display-only. The click itself is always cancelled by the
 * view, so the action runs instead of any item movement, never alongside it.
 */
public record Cell(ItemStack icon, @Nullable Consumer<Player> onClick) {

    public static Cell icon(ItemStack icon) {
        return new Cell(icon, null);
    }

    void bind(BukkitItemComponentBuilder item) {
        item.withItem(icon);
        if (onClick != null) {
            item.onClick(click -> onClick.accept(click.getPlayer()));
        }
    }
}
