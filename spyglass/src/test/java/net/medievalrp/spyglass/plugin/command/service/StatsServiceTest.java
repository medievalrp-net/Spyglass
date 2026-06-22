package net.medievalrp.spyglass.plugin.command.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import net.kyori.adventure.text.Component;
import net.medievalrp.spyglass.plugin.pipeline.IngestStats;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

/**
 * #168: /spyglass stats rendering. A null {@link IngestStats} (analytics off)
 * sends one "disabled" message; an enabled stats object sends a header plus the
 * snapshot lines.
 */
class StatsServiceTest {

    @Test
    void disabledStatsSendsASingleEnableHint() {
        CommandSender sender = mock(CommandSender.class);
        new StatsService(null).execute(sender);
        verify(sender, times(1)).sendMessage(any(Component.class));
    }

    @Test
    void enabledStatsSendsHeaderPlusSnapshotLines() {
        IngestStats stats = new IngestStats(() -> 3, () -> 42L, () -> 0L, () -> -1L);
        stats.onRecord("break");
        stats.onRecord("place");
        StatsService service = new StatsService(stats);

        CommandSender sender = mock(CommandSender.class);
        service.execute(sender);

        // Header + at least the summary line (>= 2 messages).
        verify(sender, atLeast(2)).sendMessage(any(Component.class));
    }
}
