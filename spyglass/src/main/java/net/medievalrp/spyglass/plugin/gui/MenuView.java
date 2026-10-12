package net.medievalrp.spyglass.plugin.gui;

import me.devnatan.inventoryframework.View;
import me.devnatan.inventoryframework.ViewConfigBuilder;
import me.devnatan.inventoryframework.context.Context;
import me.devnatan.inventoryframework.context.OpenContext;
import me.devnatan.inventoryframework.context.RenderContext;
import org.jetbrains.annotations.NotNull;

/** Renders a {@link Menu} handed in as the open's initial data. */
final class MenuView extends View {

    @Override
    public void onInit(@NotNull ViewConfigBuilder config) {
        // Extract-only: every click, drag, drop and pickup is cancelled while
        // open; cell actions run instead of any item movement.
        config.cancelInteractions().size(6);
    }

    @Override
    public void onOpen(@NotNull OpenContext open) {
        Menu menu = menu(open);
        open.modifyConfig().cancelInteractions().title(menu.title()).size(menu.rows());
    }

    @Override
    public void onFirstRender(@NotNull RenderContext render) {
        menu(render).cells().forEach((slot, cell) -> cell.bind(render.slot(slot)));
    }

    private static Menu menu(Context context) {
        return (Menu) context.getInitialData();
    }
}
