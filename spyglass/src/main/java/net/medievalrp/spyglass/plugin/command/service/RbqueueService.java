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

// Backs /sg rbqueue. Subcommands:
//   list           - show in-flight, pending, and recent jobs
//   stop           - cancel the in-flight job
//   cancel <id>    - cancel a pending or in-flight job by short id
//   resume <id>    - re-run an interrupted rollback
@ApiStatus.Internal
public final class RbqueueService {

    private final RollbackJobQueue queue;
    private final RollbackResumeStore resumeStore;
    private final RollbackService rollbackService;
    private final ServiceSupport support;

    public RbqueueService(RollbackJobQueue queue,
                          RollbackResumeStore resumeStore,
                          RollbackService rollbackService,
                          ServiceSupport support) {
        this.queue = queue;
        this.resumeStore = resumeStore;
        this.rollbackService = rollbackService;
        this.support = support;
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
        if (trimmed.toLowerCase(java.util.Locale.ROOT).startsWith("cancel ")) {
            cancel(sender, trimmed.substring("cancel ".length()).trim());
            return;
        }
        if (trimmed.toLowerCase(java.util.Locale.ROOT).startsWith("cancel")) {
            stop(sender);
            return;
        }
        if (trimmed.toLowerCase(java.util.Locale.ROOT).startsWith("resume ")) {
            resume(sender, trimmed.substring("resume ".length()).trim());
            return;
        }
        sender.sendMessage(Feedback.error(
                "Usage: /sg rbqueue [list | stop | cancel <id> | resume <id>]"));
    }

    private void list(CommandSender sender) {
        // queue.snapshot() is in-memory (cheap, stays on the calling thread);
        // resumeStore.listPending() enumerates + parses every .resume file on
        // disk, so it must not sit on the tick. Read off-thread, render back
        // on the main thread.
        RollbackJobQueue.Snapshot snap = queue.snapshot();
        support.onAsyncThread(() -> {
            List<RollbackResumeStore.Saved> resumePending = resumeStore.listPending();
            support.onMainThread(() -> renderList(sender, snap, resumePending));
        });
    }

    private void renderList(CommandSender sender, RollbackJobQueue.Snapshot snap,
                            List<RollbackResumeStore.Saved> resumePending) {
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
                                + " - query: " + s.query() + " - started " + s.startedAt())
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
        // Only the resume-file enumeration is disk I/O; read it off-thread,
        // then do the queue mutation (and the single small deleteFile/markFinish
        // write) back on the main thread, where the rollback queue is driven.
        support.onAsyncThread(() -> {
            List<RollbackResumeStore.Saved> pending = resumeStore.listPending();
            support.onMainThread(() -> cancelResolved(sender, idArg, pending));
        });
    }

    private void cancelResolved(CommandSender sender, String idArg,
                                List<RollbackResumeStore.Saved> pending) {
        UUID jobId = resolveShortId(idArg);
        // Resume entries first; cancel here means "discard".
        if (jobId != null) {
            for (var saved : pending) {
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
            // The pending->cancelled path skips runJob's cleanup, so
            // delete the resume marker explicitly.
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
        // Read the resume files off-thread; resumeFromSaved launches a rollback
        // and must stay on the main thread, so bounce back before calling it.
        support.onAsyncThread(() -> {
            List<RollbackResumeStore.Saved> pending = resumeStore.listPending();
            support.onMainThread(() -> resumeResolved(sender, idArg, pending));
        });
    }

    private void resumeResolved(CommandSender sender, String idArg,
                                List<RollbackResumeStore.Saved> pending) {
        for (var saved : pending) {
            if (saved.id().toString().toLowerCase(java.util.Locale.ROOT).startsWith(idArg.toLowerCase(java.util.Locale.ROOT))
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

    // Accepts an 8-char short id or a full UUID, matching against
    // the live in-flight, pending, and recent sets.
    private UUID resolveShortId(String idArg) {
        if (idArg.length() == 36) {
            try { return UUID.fromString(idArg); } catch (IllegalArgumentException ignored) { return null; }
        }
        String prefix = idArg.toLowerCase(java.util.Locale.ROOT);
        RollbackJobQueue.Snapshot snap = queue.snapshot();
        for (RollbackJob j : allJobs(snap)) {
            if (j.id.toString().toLowerCase(java.util.Locale.ROOT).startsWith(prefix)) {
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
