package net.medievalrp.spyglass.plugin.command.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.medievalrp.spyglass.plugin.command.render.Feedback;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.ApiStatus;

/**
 * Backs the {@code /sg rbqueue} command. Subcommands:
 * <ul>
 *   <li>{@code /sg rbqueue} (or {@code /sg rbqueue list}) — show
 *       in-flight + pending + recent finished jobs.</li>
 *   <li>{@code /sg rbqueue cancel <id>} — cancel a pending job by
 *       short id, or send a stop signal to the in-flight job. The
 *       engine sees the flag at the next chunk boundary and stops.</li>
 *   <li>{@code /sg rbqueue stop} — convenience for cancelling the
 *       in-flight job without typing its id.</li>
 * </ul>
 */
@ApiStatus.Internal
public final class RbqueueService {

    private final RollbackJobQueue queue;
    private final RollbackResumeStore resumeStore;
    private final RollbackService rollbackService;

    public RbqueueService(RollbackJobQueue queue,
                          RollbackResumeStore resumeStore,
                          RollbackService rollbackService) {
        this.queue = queue;
        this.resumeStore = resumeStore;
        this.rollbackService = rollbackService;
    }

    public void execute(CommandSender sender, String args) {
        String trimmed = args == null ? "" : args.trim();
        if (trimmed.isEmpty() || trimmed.equalsIgnoreCase("list")) {
            list(sender);
            return;
        }
        if (trimmed.equalsIgnoreCase("stop")) {
            stop(sender);
            return;
        }
        if (trimmed.toLowerCase().startsWith("cancel ")) {
            cancel(sender, trimmed.substring("cancel ".length()).trim());
            return;
        }
        if (trimmed.toLowerCase().startsWith("cancel")) {
            stop(sender);
            return;
        }
        if (trimmed.toLowerCase().startsWith("resume ")) {
            resume(sender, trimmed.substring("resume ".length()).trim());
            return;
        }
        sender.sendMessage(Feedback.error(
                "Usage: /sg rbqueue [list | stop | cancel <id> | resume <id>]"));
    }

    private void list(CommandSender sender) {
        RollbackJobQueue.Snapshot snap = queue.snapshot();
        var resumePending = resumeStore.listPending();
        if (snap.inFlight() == null && snap.pending().isEmpty()
                && snap.recent().isEmpty() && resumePending.isEmpty()) {
            sender.sendMessage(Feedback.bonus("No rollbacks running, queued, recent, or resumable."));
            return;
        }
        if (!resumePending.isEmpty()) {
            sender.sendMessage(Component.text("» Resumable (interrupted by previous shutdown):")
                    .color(NamedTextColor.RED));
            for (var s : resumePending) {
                sender.sendMessage(Component.text(
                        "  " + s.shortId() + " (" + s.mode() + ") by " + s.operatorName()
                                + " — query: " + s.query() + " — started " + s.startedAt())
                        .color(NamedTextColor.RED));
            }
            sender.sendMessage(Component.text(
                    "  Use /sg rbqueue resume <id> to re-run, /sg rbqueue cancel <id> to discard.")
                    .color(NamedTextColor.GRAY));
        }
        if (snap.inFlight() != null) {
            RollbackJob j = snap.inFlight();
            String elapsed = humanElapsed(j.startTime, Instant.now());
            sender.sendMessage(Component.text(
                    "» Running: " + j.shortId() + " (" + j.mode + ") by " + j.operatorName
                            + " — " + j.appliedCount.get() + " applied, "
                            + j.skippedCount.get() + " skipped, " + elapsed)
                    .color(NamedTextColor.AQUA));
            sender.sendMessage(Component.text("    query: " + j.query)
                    .color(NamedTextColor.GRAY));
        }
        if (!snap.pending().isEmpty()) {
            sender.sendMessage(Component.text("» Queued (" + snap.pending().size() + "):")
                    .color(NamedTextColor.YELLOW));
            int pos = 1;
            for (RollbackJob j : snap.pending()) {
                sender.sendMessage(Component.text(
                        "  " + pos + ". " + j.shortId() + " (" + j.mode + ") by "
                                + j.operatorName + " — query: " + j.query)
                        .color(NamedTextColor.GRAY));
                pos++;
            }
        }
        if (!snap.recent().isEmpty()) {
            sender.sendMessage(Component.text("» Recent:")
                    .color(NamedTextColor.DARK_GRAY));
            for (RollbackJob j : snap.recent()) {
                String runtime = (j.startTime != null && j.endTime != null)
                        ? humanElapsed(j.startTime, j.endTime) : "?";
                sender.sendMessage(Component.text(
                        "  " + j.shortId() + " " + j.state + " (" + j.mode + ") by "
                                + j.operatorName + " — " + j.appliedCount.get() + " applied, "
                                + runtime)
                        .color(NamedTextColor.DARK_GRAY));
            }
        }
    }

    private void stop(CommandSender sender) {
        var cancelled = queue.cancelInFlight();
        if (cancelled.isEmpty()) {
            sender.sendMessage(Feedback.error("No rollback in flight."));
            return;
        }
        sender.sendMessage(Component.text(
                "Cancel signal sent to " + cancelled.get().shortId()
                        + ". Current chunk will finish, then the rollback stops.")
                .color(NamedTextColor.YELLOW));
    }

    private void cancel(CommandSender sender, String idArg) {
        UUID jobId = resolveShortId(idArg);
        // Match resume entries first (operator may want to discard one)
        if (jobId != null) {
            for (var saved : resumeStore.listPending()) {
                if (saved.id().equals(jobId)) {
                    resumeStore.deleteFile(saved.file());
                    sender.sendMessage(Component.text(
                            "Discarded resume entry " + saved.shortId() + ".")
                            .color(NamedTextColor.YELLOW));
                    return;
                }
            }
        }
        if (jobId == null) {
            sender.sendMessage(Feedback.error("Unknown job id: " + idArg
                    + ". Use /sg rbqueue list to see ids."));
            return;
        }
        var cancelled = queue.cancel(jobId);
        if (cancelled.isEmpty()) {
            sender.sendMessage(Feedback.error("No matching pending or in-flight job."));
            return;
        }
        RollbackJob job = cancelled.get();
        if (job.state == RollbackJob.State.CANCELLED) {
            // Pending → cancelled. The resume marker was written at
            // submit time (so pending jobs survive restart); since
            // the job never ran, runJob's normal cleanup never fires.
            // Delete the marker explicitly so it doesn't show up as
            // resumable on next startup.
            resumeStore.markFinish(job.id);
            sender.sendMessage(Component.text(
                    "Removed " + job.shortId() + " from queue.")
                    .color(NamedTextColor.YELLOW));
        } else {
            sender.sendMessage(Component.text(
                    "Cancel signal sent to " + job.shortId() + " (in flight).")
                    .color(NamedTextColor.YELLOW));
        }
    }

    private void resume(CommandSender sender, String idArg) {
        for (var saved : resumeStore.listPending()) {
            if (saved.id().toString().toLowerCase().startsWith(idArg.toLowerCase())
                    || saved.shortId().equalsIgnoreCase(idArg)) {
                if (rollbackService.resumeFromSaved(saved, sender)) {
                    sender.sendMessage(Component.text(
                            "Resuming " + saved.shortId() + " — re-running query: " + saved.query())
                            .color(NamedTextColor.GREEN));
                }
                return;
            }
        }
        sender.sendMessage(Feedback.error("No resumable job matching " + idArg
                + ". Use /sg rbqueue list to see resumables."));
    }

    /** Resolve an 8-char short id (or full UUID) against the live
     *  in-flight + pending + recent set. Returns null on no match. */
    private UUID resolveShortId(String idArg) {
        // Allow either short (8 hex) or full UUID
        if (idArg.length() == 36) {
            try { return UUID.fromString(idArg); } catch (IllegalArgumentException ignored) { return null; }
        }
        String prefix = idArg.toLowerCase();
        RollbackJobQueue.Snapshot snap = queue.snapshot();
        for (RollbackJob j : allJobs(snap)) {
            if (j.id.toString().toLowerCase().startsWith(prefix)) {
                return j.id;
            }
        }
        return null;
    }

    private List<RollbackJob> allJobs(RollbackJobQueue.Snapshot snap) {
        java.util.ArrayList<RollbackJob> all = new java.util.ArrayList<>();
        if (snap.inFlight() != null) all.add(snap.inFlight());
        all.addAll(snap.pending());
        all.addAll(snap.recent());
        return all;
    }

    private String humanElapsed(Instant a, Instant b) {
        if (a == null || b == null) return "?";
        long ms = Duration.between(a, b).toMillis();
        if (ms < 1000) return ms + " ms";
        if (ms < 60_000) return String.format("%.1f s", ms / 1000.0);
        long s = ms / 1000;
        return (s / 60) + "m " + (s % 60) + "s";
    }
}
