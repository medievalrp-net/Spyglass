package net.medievalrp.spyglass.plugin.gui;

import java.util.Map;
import net.kyori.adventure.text.Component;

/** A fixed chest menu: {@code rows} (1-6) rows, cells keyed by slot index. */
public record Menu(Component title, int rows, Map<Integer, Cell> cells) {
}
