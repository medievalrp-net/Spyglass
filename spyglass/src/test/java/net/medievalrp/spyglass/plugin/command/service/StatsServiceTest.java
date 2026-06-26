package net.medievalrp.spyglass.plugin.command.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.medievalrp.spyglass.plugin.pipeline.AsyncRecorder;
import net.medievalrp.spyglass.plugin.pipeline.IngestStats;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

/**
 * #168 / #180: /spyglass stats rendering. The spill backlog line shows whenever
 * spill is enabled (regardless of analytics); a null {@link IngestStats}
 * (analytics off) sends one "disabled" hint; an enabled stats object sends a
 * header plus the snapshot lines.
 */
class StatsServiceTest {

    /** Spill disabled — renderSpillBacklog sends nothing. */
    private static Supplier<AsyncRecorder.SpillSnapshot> noSpill() {
        return () -> new AsyncRecorder.SpillSnapshot(false, 0, 0, 0, 0);
    }

    @Test
    void disabledStatsSendsASingleEnableHint() {
        CommandSender sender = mock(CommandSender.class);
        new StatsService(null, noSpill()).execute(sender);
        verify(sender, times(1)).sendMessage(any(Component.class));
    }

    @Test
    void enabledStatsSendsHeaderPlusSnapshotLines() {
        IngestStats stats = new IngestStats(() -> 3, () -> 42L, () -> 0L, () -> -1L);
        stats.onRecord("break");
        stats.onRecord("place");
        StatsService service = new StatsService(stats, noSpill());

        CommandSender sender = mock(CommandSender.class);
        service.execute(sender);

        // Header + at least the summary line (>= 2 messages).
        verify(sender, atLeast(2)).sendMessage(any(Component.class));
    }

    @Test
    void spillBacklogLineShowsEvenWhenAnalyticsIsOff() {
        // #180: a non-empty backlog must be visible without analytics enabled —
        // the spill line plus the analytics-disabled hint = two messages.
        Supplier<AsyncRecorder.SpillSnapshot> backlog =
                () -> new AsyncRecorder.SpillSnapshot(true, 3, 300, 12_345, 20_000);
        CommandSender sender = mock(CommandSender.class);
        new StatsService(null, backlog).execute(sender);
        verify(sender, times(2)).sendMessage(any(Component.class));
    }
}
