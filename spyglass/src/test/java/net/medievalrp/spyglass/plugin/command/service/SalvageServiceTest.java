package net.medievalrp.spyglass.plugin.command.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import net.kyori.adventure.text.Component;
import net.medievalrp.spyglass.plugin.salvage.SalvageGui;
import net.medievalrp.spyglass.plugin.salvage.SalvageStore;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

/**
 * #151: the console / RCON branch of {@code /sg inventory} calls the blocking
 * {@link SalvageStore#list(int)} DB query. It must run off the command (main)
 * thread; the text listing renders back on main. (The player branch goes
 * through {@link SalvageGui}, which defers its own reads.)
 */
class SalvageServiceTest {

    @Test
    void consoleListingReadsStoreOffTheMainThread() {
        SalvageStore store = mock(SalvageStore.class);
        when(store.list(anyInt())).thenReturn(List.of());
        SalvageGui gui = mock(SalvageGui.class);
        CommandSender console = mock(CommandSender.class); // not a Player -> text path
        List<Component> captured = ServiceTestSupport.captureMessages(console);
        ServiceTestSupport.RecordingSupport support = new ServiceTestSupport.RecordingSupport();

        SalvageService service = new SalvageService(store, gui, 100, support);
        service.execute(console);

        // The DB query is queued on the async pool, not run inline.
        verify(store, never()).list(anyInt());

        support.drain();
        verify(store).list(100);
        assertThat(ServiceTestSupport.plainTexts(captured))
                .anyMatch(line -> line.contains("No salvaged inventories"));
    }
}
