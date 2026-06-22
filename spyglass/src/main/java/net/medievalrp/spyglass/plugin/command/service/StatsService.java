package net.medievalrp.spyglass.plugin.command.service;

import net.medievalrp.spyglass.plugin.command.render.Feedback;
import net.medievalrp.spyglass.plugin.pipeline.IngestStats;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Renders the on-demand ingest analytics snapshot for {@code /spyglass stats}
 * (#168). A {@code null} {@link IngestStats} means analytics is disabled, in
 * which case the command explains how to turn it on.
 *
 * <p>Rates are reported as the average since analytics was enabled (a stable
 * figure that needs no main-thread sleep); the periodic console report carries
 * the live per-interval rates. Gauges (queue depth, totals, allocation) are the
 * current values either way.
 */
@ApiStatus.Internal
public final class StatsService {

    private final IngestStats stats;             // null when analytics is disabled
    private final IngestStats.Snapshot baseline; // captured at enable time

    public StatsService(@Nullable IngestStats stats) {
        this.stats = stats;
        this.baseline = stats == null ? null : stats.capture(System.nanoTime());
    }

    public void execute(CommandSender sender) {
        if (stats == null) {
            sender.sendMessage(Feedback.warn(
                    "Spyglass analytics is disabled. Set analytics.enabled = true in "
                            + "config.conf and restart to collect ingest stats."));
            return;
        }
        IngestStats.Snapshot now = stats.capture(System.nanoTime());
        sender.sendMessage(Feedback.success("Spyglass ingest analytics (avg since enabled):"));
        for (String line : IngestStats.describe(baseline, now)) {
            sender.sendMessage(Feedback.bonus(line));
        }
    }
}
