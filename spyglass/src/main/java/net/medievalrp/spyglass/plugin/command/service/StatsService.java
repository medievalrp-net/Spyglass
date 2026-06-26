package net.medievalrp.spyglass.plugin.command.service;

import java.util.Locale;
import java.util.function.Supplier;
import net.medievalrp.spyglass.plugin.command.render.Feedback;
import net.medievalrp.spyglass.plugin.pipeline.AsyncRecorder;
import net.medievalrp.spyglass.plugin.pipeline.IngestStats;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Renders {@code /spyglass stats}: the on-disk spill backlog (#180) followed by
 * the opt-in ingest analytics snapshot (#168).
 *
 * <p>The spill backlog is shown <b>regardless</b> of whether analytics is
 * enabled — it is the operator's gauge for reclaiming an on-disk overflow (a big
 * WorldEdit paste that spilled), and analytics is off by default. A {@code null}
 * {@link IngestStats} means analytics is disabled, in which case the analytics
 * section is replaced by a hint on how to turn it on.
 *
 * <p>Rates are reported as the average since analytics was enabled (a stable
 * figure that needs no main-thread sleep); the periodic console report carries
 * the live per-interval rates. Gauges (queue depth, totals, allocation, spill
 * backlog) are the current values either way.
 */
@ApiStatus.Internal
public final class StatsService {

    private final IngestStats stats;             // null when analytics is disabled
    private final IngestStats.Snapshot baseline; // captured at enable time
    private final Supplier<AsyncRecorder.SpillSnapshot> spill;

    public StatsService(@Nullable IngestStats stats,
                        Supplier<AsyncRecorder.SpillSnapshot> spill) {
        this.stats = stats;
        this.baseline = stats == null ? null : stats.capture(System.nanoTime());
        this.spill = spill;
    }

    public void execute(CommandSender sender) {
        renderSpillBacklog(sender);
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

    private void renderSpillBacklog(CommandSender sender) {
        AsyncRecorder.SpillSnapshot snap = spill.get();
        if (!snap.enabled()) {
            return; // spill-to-disk off (or no queue ceiling): nothing to report
        }
        if (snap.records() <= 0) {
            sender.sendMessage(Feedback.success("Spill backlog: empty (all overflow drained)."));
            return;
        }
        String rate = snap.drainRatePerSec() <= 0
                ? "unlimited"
                : snap.drainRatePerSec() + " rec/s";
        sender.sendMessage(Feedback.warn(String.format(Locale.ROOT,
                "Spill backlog: %d record(s) in %d segment(s), %.1f MiB on disk, draining at "
                        + "up to %s. These records are durable but not queryable until replayed; "
                        + "raise storage.spill-drain-rate to recover faster.",
                snap.records(), snap.segments(), snap.bytes() / 1_048_576.0, rate)));
    }
}
