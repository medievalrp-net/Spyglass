package net.medievalrp.spyglass.plugin.command.service;

import net.medievalrp.spyglass.plugin.command.render.Feedback;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.World;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.medievalrp.spyglass.api.SpyglassApi;
import net.medievalrp.spyglass.api.event.EventRecord;
import net.medievalrp.spyglass.api.param.ParamParseException;
import net.medievalrp.spyglass.api.query.Flag;
import net.medievalrp.spyglass.api.query.QueryRequest;
import net.medievalrp.spyglass.api.query.QueryResult;
import net.medievalrp.spyglass.api.query.Sort;
import net.medievalrp.spyglass.api.rollback.RollbackEffect;
import net.medievalrp.spyglass.api.rollback.RollbackResult;
import net.medievalrp.spyglass.api.rollback.Rollbackable;
import net.medievalrp.spyglass.plugin.command.param.QueryStringParser;
import net.medievalrp.spyglass.plugin.config.SpyglassConfig;
import net.medievalrp.spyglass.plugin.rollback.RollbackEngine;
import net.medievalrp.spyglass.plugin.rollback.UndoStack;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class RollbackService {

    private final SpyglassApi api;
    private final QueryStringParser parser;
    private final SpyglassConfig config;
    private final RollbackEngine engine;
    private final UndoStack undoStack;
    private final ServiceSupport support;
    private final net.medievalrp.spyglass.plugin.pipeline.Recorder recorder;
    private final net.medievalrp.spyglass.plugin.storage.RecordStore store;
    private final Logger logger;
    private final RollbackJobQueue jobQueue;
    private final RollbackResumeStore resumeStore;
    // Parsed request for each queued job, popped when the job runs.
    private final Map<UUID, JobContext> pendingContexts = new ConcurrentHashMap<>();

    public RollbackService(SpyglassApi api,
                           QueryStringParser parser,
                           SpyglassConfig config,
                           RollbackEngine engine,
                           UndoStack undoStack,
                           ServiceSupport support,
                           net.medievalrp.spyglass.plugin.pipeline.Recorder recorder,
                           net.medievalrp.spyglass.plugin.storage.RecordStore store,
                           Logger logger,
                           RollbackJobQueue jobQueue,
                           RollbackResumeStore resumeStore) {
        this.api = api;
        this.parser = parser;
        this.config = config;
        this.engine = engine;
        this.undoStack = undoStack;
        this.support = support;
        this.recorder = recorder;
        this.store = store;
        this.logger = logger;
        this.jobQueue = jobQueue;
        this.resumeStore = resumeStore;
    }

    public void wireQueue() {
        jobQueue.setRunner(this::runJob);
    }

    // The rollback record cap (limits.rollback-result). <= 0 means
    // "no cap" — run until the page cursor is exhausted — so a large
    // grief rollback isn't silently truncated at N records. (Same
    // 0-means-unbounded convention as defaults.radius=0 => global.)
    // Mapped to MAX_VALUE so the streaming loop's hardLimit never trips.
    private int rollbackResultLimit() {
        int configured = config.limits().rollbackResult();
        return configured <= 0 ? Integer.MAX_VALUE : configured;
    }

    // Submit a page read to the prefetch executor so it overlaps the
    // previous page's apply. cur is a parameter (not the loop's mutable
    // cursor) so the lambda capture stays effectively-final.
    private java.util.concurrent.Future<net.medievalrp.spyglass.plugin.storage.QueryPage> submitQuery(
            java.util.concurrent.ExecutorService exec, QueryRequest req,
            net.medievalrp.spyglass.plugin.storage.QueryPage.Cursor cur, int size) {
        return exec.submit(() -> store.queryPage(req, cur, size));
    }

    // Re-submit an interrupted rollback as a fresh job. Idempotent:
    // already-applied cells skip with "block changed" on the re-run.
    public boolean resumeFromSaved(RollbackResumeStore.Saved saved, CommandSender sender) {
        QueryRequest request;
        try {
            RollbackMode mode = saved.mode() == RollbackJob.Mode.RESTORE
                    ? RollbackMode.RESTORE : RollbackMode.ROLLBACK;
            request = forceNoGroup(parser.parse(sender, saved.query(),
                    rollbackResultLimit()), mode);
        } catch (ParamParseException ex) {
            sender.sendMessage(Feedback.error(
                    "Resume failed: " + ex.getMessage() + " (query: " + saved.query() + ")"));
            return false;
        }
        // Fresh job id so the current sender (which may differ from
        // the original operator) gets progress messages.
        UUID operatorId = sender instanceof Player p ? p.getUniqueId() : null;
        RollbackJob job = new RollbackJob(UUID.randomUUID(), operatorId, sender.getName(),
                saved.query(), saved.mode(), Instant.now(), sender);
        RollbackMode rmode = saved.mode() == RollbackJob.Mode.RESTORE
                ? RollbackMode.RESTORE : RollbackMode.ROLLBACK;
        // Resume from the saved cursor so we don't re-query the
        // already-applied prefix on big rollbacks.
        net.medievalrp.spyglass.plugin.storage.QueryPage.Cursor startCursor = null;
        if (saved.cursor() != null) {
            startCursor = new net.medievalrp.spyglass.plugin.storage.QueryPage.Cursor(
                    saved.cursor().occurred(), saved.cursor().id());
        }
        pendingContexts.put(job.id, new JobContext(request, rmode, startCursor,
                saved.appliedSoFar(), saved.skippedSoFar()));

        int position = jobQueue.submit(job);
        if (position > 0) {
            sender.sendMessage(Feedback.bonus(
                    "Resume queued at position " + position + " (id " + job.shortId() + ")."));
        }
        // Drop the old marker now that the new job has taken over.
        resumeStore.markFinish(saved.id());
        return true;
    }

    private record JobContext(QueryRequest request, RollbackMode mode,
                              @org.jetbrains.annotations.Nullable
                              net.medievalrp.spyglass.plugin.storage.QueryPage.Cursor startCursor,
                              int initialApplied, int initialSkipped) {
        JobContext(QueryRequest request, RollbackMode mode) {
            this(request, mode, null, 0, 0);
        }
    }

    public void execute(CommandSender sender, String raw, RollbackMode mode) {
        QueryRequest request;
        try {
            request = forceNoGroup(parser.parse(sender, raw, rollbackResultLimit()), mode);
        } catch (ParamParseException ex) {
            sender.sendMessage(Feedback.error(ex.getMessage()));
            return;
        }

        // Queue serializes rollbacks so two operators can't trip
        // over each other writing the same chunk.
        UUID operatorId = sender instanceof Player p ? p.getUniqueId() : null;
        RollbackJob.Mode jobMode = mode == RollbackMode.RESTORE
                ? RollbackJob.Mode.RESTORE
                : RollbackJob.Mode.ROLLBACK;
        RollbackJob job = new RollbackJob(UUID.randomUUID(), operatorId, sender.getName(),
                raw, jobMode, Instant.now(), sender);
        pendingContexts.put(job.id, new JobContext(request, mode));

        // Persist before queueing so pending jobs also survive a
        // restart and show up as resumable.
        resumeStore.markStart(job.id, job.operatorName, job.operatorId, job.query, job.mode);

        int position = jobQueue.submit(job);
        if (position > 0) {
            sender.sendMessage(Feedback.bonus("Rollback queued at position " + position
                    + " (id " + job.shortId() + "). View / cancel with /sg rbqueue."));
        }
    }

    public void runJob(RollbackJob job) {
        JobContext ctx = pendingContexts.remove(job.id);
        if (ctx == null) {
            // Job was submitted outside execute(); drop it.
            jobQueue.finish(job, RollbackJob.State.FAILED);
            return;
        }
        job.sender.sendMessage(Feedback.querying());
        resumeStore.markStart(job.id, job.operatorName, job.operatorId, job.query, job.mode);
        net.medievalrp.spyglass.api.util.Duration flushTimeout =
                config.limits().rollbackFlushTimeout();
        // Flush the recorder first so the store has caught up to
        // in-flight events, then keyset-paginate and apply each page
        // before fetching the next. Memory stays O(pageSize).
        support.onAsyncThread(() -> {
            try {
                boolean drained = recorder.flush(flushTimeout);
                if (!drained) {
                    support.onMainThread(() -> job.sender.sendMessage(Feedback.bonus(
                            "Recorder still draining; rollback may miss the most recent events.")));
                }
                streamPagesAndApply(job, ctx.request(), ctx.mode(),
                        ctx.startCursor(), ctx.initialApplied(), ctx.initialSkipped());
                // Cancellation wins over done if the operator hit
                // /sg rbqueue cancel mid-flight.
                jobQueue.finish(job, job.cancelFlag.get()
                        ? RollbackJob.State.CANCELLED
                        : RollbackJob.State.DONE);
                resumeStore.markFinish(job.id);
            } catch (Throwable thrown) {
                logger.warning("Spyglass rollback job " + job.shortId() + " failed: " + thrown);
                job.failureMessage = thrown.getMessage();
                jobQueue.finish(job, RollbackJob.State.FAILED);
                // Leave the resume marker so the operator can re-run.
            }
        });
    }

    // Keyset-paginate through the store and apply each page through
    // the chunked engine. The apply future blocks here before we
    // fetch the next page, keeping heap bounded.
    private void streamPagesAndApply(RollbackJob job, QueryRequest request, RollbackMode mode,
                                     @org.jetbrains.annotations.Nullable
                                     net.medievalrp.spyglass.plugin.storage.QueryPage.Cursor startCursor,
                                     int initialApplied, int initialSkipped) {
        CommandSender sender = job.sender;
        AtomicBoolean cancelFlag = job.cancelFlag;
        long startNanos = System.nanoTime();
        int pageSize = Math.max(100, config.limits().rollbackPageSize());
        int batchSize = config.limits().rollbackBatchSize();
        int hardLimit = request.limit();
        int totalApplied = initialApplied;
        int totalSkipped = initialSkipped;
        int totalErrors = 0;
        int totalSeen = 0;
        // Wall-clock phase timers (#9): where a rollback's time actually
        // goes. query = ClickHouse fetch + record decode; collect = build
        // effects; prewarm = chunk load; apply = the chunked engine run
        // (off-main writes + main post-processing + inter-tick yields +
        // the audit emit).
        long queryNanos = 0L, collectNanos = 0L, prewarmNanos = 0L, applyNanos = 0L;
        int pageCount = 0;
        java.util.HashSet<String> chunkKeys = new java.util.HashSet<>();
        java.util.LinkedHashMap<String, Integer> skipCounts = new java.util.LinkedHashMap<>();
        // Past undoCap inverses we drop the rest. The rollback still
        // applies; only /spyglass undo is sacrificed. Without this a
        // 1M-block rollback eats ~200 MB just holding inverses.
        int undoCap = Math.max(0, config.limits().rollbackUndoCap());
        List<RollbackEffect> inverses = new ArrayList<>();
        boolean undoTruncated = false;

        net.medievalrp.spyglass.plugin.storage.QueryPage.Cursor cursor = startCursor;
        if (startCursor != null) {
            support.onMainThread(() -> sender.sendMessage(Feedback.bonus(
                    "Resuming from cursor: " + initialApplied + " applied + "
                            + initialSkipped + " skipped before crash.")));
        }
        // Prefetch pipeline: the next page's ClickHouse read runs on this
        // executor while the current page applies, so the query phase (the
        // timings show it is ~half the rollback wall-clock) overlaps the
        // off-main apply instead of running serially before it. At most one
        // read is in flight, so heap holds ~two pages of records.
        java.util.concurrent.ExecutorService prefetch =
                java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "Spyglass-RollbackPrefetch");
                    t.setDaemon(true);
                    return t;
                });
        java.util.concurrent.Future<net.medievalrp.spyglass.plugin.storage.QueryPage> pending =
                submitQuery(prefetch, request, cursor, Math.min(pageSize, hardLimit));
        try {
            while (pending != null) {
                // Cancellation is checked between pages so the
                // current page finishes cleanly.
                if (cancelFlag.get()) {
                    break;
                }
                net.medievalrp.spyglass.plugin.storage.QueryPage page;
                long tQuery = System.nanoTime();
                try {
                    page = pending.get();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (java.util.concurrent.ExecutionException ee) {
                    Throwable cause = ee.getCause() == null ? ee : ee.getCause();
                    logger.warning("Spyglass " + mode.label() + " query page failed: " + cause);
                    final String msg = cause.getMessage();
                    support.onMainThread(() -> sender.sendMessage(
                            Feedback.error(mode.label() + " failed: " + msg)));
                    return;
                }
                // tQuery now measures only the *wait* for the prefetch — ~0
                // once the pipeline is warm, because the read completed during
                // the previous page's apply.
                queryNanos += System.nanoTime() - tQuery;
                pageCount++;
                if (page.records().isEmpty()) {
                    break;
                }
                totalSeen += page.records().size();
                net.medievalrp.spyglass.plugin.storage.QueryPage.Cursor next = page.next();
                // Kick off the next page's read now, so it runs during this
                // page's apply rather than after it.
                if (next != null && totalSeen < hardLimit) {
                    pending = submitQuery(prefetch, request, next,
                            Math.min(pageSize, hardLimit - totalSeen));
                } else {
                    pending = null;
                }
                long tCollect = System.nanoTime();
                List<RollbackEffect> effects = collectEffectsFromRecords(page.records(), mode);
                collectNanos += System.nanoTime() - tCollect;
                if (effects.isEmpty()) {
                    cursor = next;
                    continue;
                }
                // Pre-warm chunks off-thread, then apply. Blocking
                // on the apply future before fetching the next page
                // keeps heap bounded to one page's effects.
                long tPrewarm = System.nanoTime();
                preWarmChunksForRecords(page.records()).join();
                prewarmNanos += System.nanoTime() - tPrewarm;
                List<RollbackResult> results;
                long tApply = System.nanoTime();
                try {
                    java.util.concurrent.CompletableFuture<List<RollbackResult>> fut =
                            new java.util.concurrent.CompletableFuture<>();
                    support.onMainThread(() ->
                            engine.applyAllChunked(effects, sender, support, batchSize, cancelFlag)
                                    .whenComplete((r, err) -> {
                                        if (err != null) fut.completeExceptionally(err);
                                        else fut.complete(r);
                                    }));
                    results = fut.join();
                } catch (java.util.concurrent.CompletionException ex) {
                    Throwable cause = ex.getCause() == null ? ex : ex.getCause();
                    logger.warning("Spyglass " + mode.label() + " page apply failed: " + cause);
                    final String msg = cause.getMessage() == null ? cause.toString() : cause.getMessage();
                    support.onMainThread(() -> sender.sendMessage(
                            Feedback.error(mode.label() + " failed: " + msg)));
                    return;
                }
                applyNanos += System.nanoTime() - tApply;
                for (RollbackResult r : results) {
                    if (r instanceof RollbackResult.Applied applied) {
                        totalApplied++;
                        if (totalApplied <= undoCap) {
                            inverses.add(applied.inverseEffect());
                        } else {
                            undoTruncated = true;
                        }
                        BlockLocation loc = locationOf(applied.effect());
                        if (loc != null) {
                            chunkKeys.add(loc.worldId() + ":"
                                    + (loc.x() >> 4) + ":" + (loc.z() >> 4));
                        }
                    } else if (r instanceof RollbackResult.Skipped skipped) {
                        totalSkipped++;
                        skipCounts.merge(skipped.reason().message(), 1, Integer::sum);
                        // RollbackReason.Error is a real failure;
                        // BlockChanged / InvalidLocation are benign.
                        if (skipped.reason()
                                instanceof net.medievalrp.spyglass.api.rollback.RollbackReason.Error) {
                            totalErrors++;
                        }
                    }
                }
                job.appliedCount.set(totalApplied);
                job.skippedCount.set(totalSkipped);
                if (sender instanceof Player progressTarget) {
                    int appliedSoFar = totalApplied;
                    int skippedSoFar = totalSkipped;
                    support.onMainThread(() -> progressTarget.sendActionBar(
                            net.kyori.adventure.text.Component.text(
                                    "Rolling back: " + appliedSoFar + " applied, " + skippedSoFar + " skipped")));
                }
                cursor = next;
                // Checkpoint to the resume marker so a crash picks
                // up from here rather than re-querying from the start.
                RollbackResumeStore.Cursor resumeCursor = cursor == null ? null
                        : new RollbackResumeStore.Cursor(cursor.occurred(), cursor.id());
                resumeStore.markProgress(job.id, job.operatorName, job.operatorId,
                        job.query, job.mode, job.submitTime,
                        resumeCursor, totalApplied, totalSkipped);
                if (cursor == null) {
                    break;
                }
            }
        } catch (RuntimeException unexpected) {
            logger.warning("Spyglass " + mode.label() + " streaming failure: " + unexpected);
            final String msg = unexpected.getMessage() == null ? unexpected.toString() : unexpected.getMessage();
            support.onMainThread(() -> sender.sendMessage(
                    Feedback.error(mode.label() + " failed: " + msg)));
            return;
        } finally {
            // Tear down the prefetch thread on every exit path (normal,
            // early-return, or exception); shutdownNow interrupts an
            // in-flight read that the loop already broke away from.
            prefetch.shutdownNow();
        }

        if (totalSeen == 0) {
            support.onMainThread(() -> sender.sendMessage(Feedback.error("No results.")));
            return;
        }

        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
        logger.info(String.format(
                "Spyglass %s %s timings: query=%dms collect=%dms prewarm=%dms apply=%dms"
                        + " | %d pages, %d chunks, %d applied, %dms total",
                mode.label(), job.shortId(),
                queryNanos / 1_000_000L, collectNanos / 1_000_000L,
                prewarmNanos / 1_000_000L, applyNanos / 1_000_000L,
                pageCount, chunkKeys.size(), totalApplied, elapsedMs));
        Summary summary = new Summary(totalApplied, totalSkipped, totalErrors,
                chunkKeys.size(), elapsedMs, inverses);
        boolean truncated = undoTruncated;
        support.onMainThread(() -> deliverStreamingSummary(sender, mode, summary, skipCounts, truncated));
    }

    // Pre-warm chunks via getChunkAtAsync so the apply phase doesn't
    // pay a chunk-load stall inside its tick budget. No-op outside
    // a live Bukkit server (tests).
    private CompletableFuture<Void> preWarmChunksForRecords(List<EventRecord> records) {
        try {
            Map<UUID, Set<Long>> byWorld = new HashMap<>();
            for (EventRecord record : records) {
                if (!(record instanceof Rollbackable)) continue;
                BlockLocation loc = record.location();
                if (loc == null) continue;
                long packed = ((long) (loc.x() >> 4) << 32) | ((loc.z() >> 4) & 0xFFFFFFFFL);
                byWorld.computeIfAbsent(loc.worldId(), k -> new HashSet<>()).add(packed);
            }
            if (byWorld.isEmpty() || Bukkit.getServer() == null) {
                return CompletableFuture.completedFuture(null);
            }
            List<CompletableFuture<?>> futures = new ArrayList<>();
            for (Map.Entry<UUID, Set<Long>> entry : byWorld.entrySet()) {
                World world = Bukkit.getWorld(entry.getKey());
                if (world == null) continue;
                for (long packed : entry.getValue()) {
                    int cx = (int) (packed >> 32);
                    int cz = (int) packed;
                    futures.add(world.getChunkAtAsync(cx, cz));
                }
            }
            if (futures.isEmpty()) return CompletableFuture.completedFuture(null);
            return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
        } catch (Throwable thrown) {
            return CompletableFuture.completedFuture(null);
        }
    }

    private static List<RollbackEffect> collectEffectsFromRecords(List<EventRecord> records, RollbackMode mode) {
        List<RollbackEffect> effects = new ArrayList<>(records.size());
        for (EventRecord record : records) {
            if (record instanceof Rollbackable rollbackable) {
                effects.add(mode == RollbackMode.ROLLBACK
                        ? rollbackable.rollbackEffect()
                        : rollbackable.restoreEffect());
            }
        }
        return effects;
    }

    private void deliverStreamingSummary(CommandSender sender, RollbackMode mode,
                                         Summary summary,
                                         java.util.LinkedHashMap<String, Integer> skipCounts,
                                         boolean undoTruncated) {
        for (var entry : skipCounts.entrySet()) {
            String suffix = entry.getValue() == 1 ? "" : " ×" + entry.getValue();
            sender.sendMessage(Feedback.bonus("Skip Reason: " + entry.getKey() + suffix));
        }
        sender.sendMessage(summaryLine(summary));
        if (undoTruncated) {
            sender.sendMessage(Feedback.bonus(
                    "Rollback exceeded undo cap (" + config.limits().rollbackUndoCap()
                            + "); /spyglass undo will not reverse this op."));
        }
        if (sender instanceof Player player && !summary.inverses.isEmpty()) {
            UUID playerId = player.getUniqueId();
            String operation = mode.name();
            List<RollbackEffect> inverses = summary.inverses;
            support.onAsyncThread(() -> {
                try {
                    undoStack.push(playerId, operation, inverses);
                } catch (RuntimeException ex) {
                    logger.warning("Spyglass undo-stack push failed (rollback "
                            + "applied but undo unavailable): " + ex.getMessage());
                }
            });
        }
    }

    private static BlockLocation locationOf(RollbackEffect effect) {
        return switch (effect) {
            case RollbackEffect.BlockReplace br -> br.location();
            case RollbackEffect.ContainerSlotWrite csw -> csw.location();
            case RollbackEffect.EntitySpawn es -> es.location();
            case RollbackEffect.EntityRemove er -> er.location();
            case RollbackEffect.Custom c -> c.location();
        };
    }

    private static QueryRequest forceNoGroup(QueryRequest request, RollbackMode mode) {
        EnumSet<Flag> flags = EnumSet.copyOf(request.flags());
        flags.add(Flag.NO_GROUP);
        Sort sort = mode == RollbackMode.ROLLBACK ? Sort.NEWEST_FIRST : Sort.OLDEST_FIRST;
        return new QueryRequest(request.predicates(), sort, request.limit(), flags, false);
    }

    public static Component summaryLine(Summary summary) {
        StringBuilder sb = new StringBuilder();
        sb.append(' ').append(summary.applied()).append(" reversals");
        if (summary.chunks() > 0) {
            sb.append(" across ").append(summary.chunks()).append(" chunk")
                    .append(summary.chunks() == 1 ? "" : "s");
        }
        if (summary.elapsedMs() > 0) {
            sb.append(" in ").append(humanMs(summary.elapsedMs()));
        }
        if (summary.skipped() > 0) {
            sb.append(". ").append(summary.skipped()).append(" skipped");
        }
        if (summary.errors() > 0) {
            sb.append(", ").append(summary.errors()).append(" error")
                    .append(summary.errors() == 1 ? "" : "s");
        }
        return Feedback.success(sb.toString());
    }

    private static String humanMs(long ms) {
        if (ms < 1_000) return ms + "ms";
        double s = ms / 1000.0;
        if (s < 60) return String.format("%.1fs", s);
        long mm = (long) (s / 60);
        long ss = (long) (s - mm * 60);
        return mm + "m" + ss + "s";
    }

    public record Summary(int applied, int skipped, int errors,
                          int chunks, long elapsedMs,
                          List<RollbackEffect> inverses) {

        public Summary {
            inverses = List.copyOf(inverses);
        }

        public Summary(int applied, int skipped, List<RollbackEffect> inverses) {
            this(applied, skipped, 0, 0, 0L, inverses);
        }
    }
}
