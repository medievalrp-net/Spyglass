package net.medievalrp.spyglass.plugin.gui;

import me.devnatan.inventoryframework.View;
import me.devnatan.inventoryframework.ViewConfigBuilder;
import me.devnatan.inventoryframework.component.Pagination;
import me.devnatan.inventoryframework.context.Context;
import me.devnatan.inventoryframework.context.OpenContext;
import me.devnatan.inventoryframework.context.RenderContext;
import me.devnatan.inventoryframework.state.State;
import org.jetbrains.annotations.NotNull;

/** Renders a {@link PagedMenu} handed in as the open's initial data. */
final class PagedMenuView extends View {

    private static final char CONTENT = 'O';
    private static final char FILLER = '#';
    private static final char BACK = 'b';
    private static final char PREVIOUS = '<';
    private static final char INFO = 'i';
    private static final char NEXT = '>';

    // 5 content rows (45 slots) + the nav row.
    private static final String[] LAYOUT = {
            "OOOOOOOOO",
            "OOOOOOOOO",
            "OOOOOOOOO",
            "OOOOOOOOO",
            "OOOOOOOOO",
            "b##<i>###"
    };

    private final State<Pagination> pages = this.<Cell>lazyPaginationState(
            (Context context) -> menu(context).content(),
            (context, item, index, cell) -> cell.bind(item));

    @Override
    public void onInit(@NotNull ViewConfigBuilder config) {
        // Extract-only: every click, drag, drop and pickup is cancelled while
        // open; cell actions run instead of any item movement.
        config.cancelInteractions().size(LAYOUT.length).layout(LAYOUT);
    }

    @Override
    public void onOpen(@NotNull OpenContext open) {
        open.modifyConfig().cancelInteractions().title(menu(open).title());
    }

    @Override
    public void onFirstRender(@NotNull RenderContext render) {
        PagedMenu menu = menu(render);
        render.layoutSlot(FILLER, (index, item) -> item.withItem(menu.filler()));
        (menu.back() != null ? menu.back() : Cell.icon(menu.filler())).bind(render.layoutSlot(BACK));
        menu.info().bind(render.layoutSlot(INFO));
        render.layoutSlot(PREVIOUS)
                .renderWith(() -> pages.get(render).canBack() ? menu.previous() : menu.filler())
                .updateOnStateChange(pages)
                .onClick(click -> {
                    Pagination pagination = pages.get(click);
                    if (pagination.canBack()) {
                        pagination.back();
                    }
                });
        render.layoutSlot(NEXT)
                .renderWith(() -> pages.get(render).canAdvance() ? menu.next() : menu.filler())
                .updateOnStateChange(pages)
                .onClick(click -> {
                    Pagination pagination = pages.get(click);
                    if (pagination.canAdvance()) {
                        pagination.advance();
                    }
                });
    }

    private static PagedMenu menu(Context context) {
        return (PagedMenu) context.getInitialData();
    }
}
