package net.medievalrp.spyglass.plugin.command.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import java.util.logging.Logger;
import net.medievalrp.spyglass.plugin.config.SpyglassConfig;
import net.medievalrp.spyglass.plugin.snapshot.SnapshotSession;
import net.medievalrp.spyglass.plugin.snapshot.SnapshotView;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class SnapshotGuiOnlyTest {
    private SnapshotService service(SnapshotView view) {
        var config = mock(SpyglassConfig.class, RETURNS_DEEP_STUBS);
        return new SnapshotService(null, null, null, config, null, null, view, Logger.getLogger("test"));
    }

    @Test
    void consoleIsRejectedBeforeReadingHistory() {
        var sender = mock(CommandSender.class);
        var messages = ServiceTestSupport.captureMessages(sender);
        var view = mock(SnapshotView.class);
        service(view).execute(sender, "p:someone t:1h");
        verifyNoInteractions(view);
        assertThat(ServiceTestSupport.plainTexts(messages)).singleElement().asString().contains("in-game");
    }

    @Test
    void unavailableGuiIsRejectedBeforeReadingHistory() {
        var player = mock(Player.class);
        var messages = ServiceTestSupport.captureMessages(player);
        service(null).execute(player, "p:someone t:1h");
        assertThat(ServiceTestSupport.plainTexts(messages)).singleElement().asString().contains("unavailable");
    }

    @Test
    void failingGuiProducesOnlyAnErrorWithoutTextInventory() {
        var player = mock(Player.class);
        var messages = ServiceTestSupport.captureMessages(player);
        var view = mock(SnapshotView.class);
        var session = mock(SnapshotSession.class);
        doThrow(new IllegalStateException("test GUI failure")).when(view).open(player, session);
        service(view).openGui(player, session);
        assertThat(ServiceTestSupport.plainTexts(messages)).singleElement().asString().contains("Could not open");
    }

    @Test
    void playerOpensGuiWithoutChatListing() {
        var player = mock(Player.class);
        var messages = ServiceTestSupport.captureMessages(player);
        var view = mock(SnapshotView.class);
        var session = mock(SnapshotSession.class);
        service(view).openGui(player, session);
        verify(view).open(player, session);
        assertThat(messages).isEmpty();
    }
}
