package net.medievalrp.spyglass.plugin.command.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.medievalrp.spyglass.plugin.salvage.SalvageStore;
import net.medievalrp.spyglass.plugin.salvage.SalvageWithdrawals;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

/**
 * The command-path branches of {@code /sg inventory}: the console / RCON (and
 * no-GUI 26.x) text listing, and the {@code <id>} withdraw guard. The listing
 * calls the blocking {@link SalvageStore#list(int)} DB query, so it must run off
 * the command (main) thread and render back on main (#151). The player-with-GUI
 * branch goes through the InvUI view and is verified in-game.
 */
class SalvageServiceTest {

    private static SalvageWithdrawals withdrawals(SalvageStore store) {
        return new SalvageWithdrawals(store, Runnable::run, null, Logger.getLogger("test"));
    }

    @Test
    void consoleListingReadsStoreOffTheMainThread() {
        SalvageStore store = mock(SalvageStore.class);
        when(store.list(anyInt())).thenReturn(List.of());
        CommandSender console = mock(CommandSender.class); // not a Player -> text path
        List<Component> captured = ServiceTestSupport.captureMessages(console);
        ServiceTestSupport.RecordingSupport support = new ServiceTestSupport.RecordingSupport();

        // No GUI view (null) - console always gets the listing anyway.
        SalvageService service = new SalvageService(store, null, withdrawals(store), 100, support);
        service.execute(console);

        // The DB query is queued on the async pool, not run inline.
        verify(store, never()).list(anyInt());

        support.drain();
        verify(store).list(100);
        assertThat(ServiceTestSupport.plainTexts(captured))
                .anyMatch(line -> line.contains("No salvaged inventories"));
    }

    @Test
    void withdrawFromConsoleIsRefusedWithoutTouchingTheStore() {
        SalvageStore store = mock(SalvageStore.class);
        CommandSender console = mock(CommandSender.class); // not a Player
        List<Component> captured = ServiceTestSupport.captureMessages(console);
        ServiceTestSupport.RecordingSupport support = new ServiceTestSupport.RecordingSupport();

        SalvageService service = new SalvageService(store, null, withdrawals(store), 100, support);
        service.withdraw(console, "0000abcd");
        support.drain();

        // Recovery is players-only; the console never resolves or mutates a snapshot.
        verify(store, never()).get(any());
        verify(store, never()).list(anyInt());
        assertThat(ServiceTestSupport.plainTexts(captured))
                .anyMatch(line -> line.toLowerCase().contains("as a player"));
    }
}
