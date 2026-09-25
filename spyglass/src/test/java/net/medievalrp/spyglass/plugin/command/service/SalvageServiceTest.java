package net.medievalrp.spyglass.plugin.command.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import java.util.logging.Logger;
import net.medievalrp.spyglass.plugin.salvage.SalvageStore;
import net.medievalrp.spyglass.plugin.salvage.SalvageView;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class SalvageServiceTest {
    @Test
    void consoleCannotListOrReadSalvage() {
        var store = mock(SalvageStore.class);
        var view = mock(SalvageView.class);
        var sender = mock(CommandSender.class);
        var messages = ServiceTestSupport.captureMessages(sender);
        new SalvageService(store, view, Logger.getLogger("test")).execute(sender);
        verifyNoInteractions(store, view);
        assertThat(ServiceTestSupport.plainTexts(messages)).singleElement().asString().contains("in-game");
    }

    @Test
    void unavailableGuiDoesNotReadOrListSalvage() {
        var store = mock(SalvageStore.class);
        var player = mock(Player.class);
        var messages = ServiceTestSupport.captureMessages(player);
        new SalvageService(store, null, Logger.getLogger("test")).execute(player);
        verifyNoInteractions(store);
        assertThat(ServiceTestSupport.plainTexts(messages)).singleElement().asString().contains("unavailable");
    }

    @Test
    void failingGuiReportsErrorWithoutListingOrRecoveringItems() {
        var store = mock(SalvageStore.class);
        var view = mock(SalvageView.class);
        var player = mock(Player.class);
        var messages = ServiceTestSupport.captureMessages(player);
        doThrow(new IllegalStateException("test GUI failure")).when(view).open(player);
        new SalvageService(store, view, Logger.getLogger("test")).execute(player);
        verifyNoInteractions(store);
        assertThat(ServiceTestSupport.plainTexts(messages)).singleElement().asString().contains("Could not open");
    }

    @Test
    void playerOpensGuiWithoutChatListing() {
        var store = mock(SalvageStore.class);
        var view = mock(SalvageView.class);
        var player = mock(Player.class);
        var messages = ServiceTestSupport.captureMessages(player);
        new SalvageService(store, view, Logger.getLogger("test")).execute(player);
        verify(view).open(player);
        verifyNoInteractions(store);
        assertThat(messages).isEmpty();
    }
}
