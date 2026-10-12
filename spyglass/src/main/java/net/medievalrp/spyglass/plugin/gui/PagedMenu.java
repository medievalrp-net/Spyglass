package net.medievalrp.spyglass.plugin.gui;

import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * A six-row menu: five rows of paged {@code content} over a nav row holding
 * back (when present), previous/next page and an info cell. A page button
 * with nowhere to go shows {@code filler} instead.
 */
public record PagedMenu(Component title, List<Cell> content, Cell info, @Nullable Cell back,
                        ItemStack filler, ItemStack previous, ItemStack next) {
}
