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
    /** Per-job parsed request, kept until the job runs (queue may
     *  delay a job behind another). Removed inside {@link #runJob}. */
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

    /** Set up the queue's runner — must be called once after
     *  construction so the queue can call back into us when a job
     *  is dispatched. Done at plugin startup in {@code SpyglassPlugin}. */
    public void wireQueue() {
        jobQueue.setRunner(this::runJob);
    }

    /**
     * Re-submit a previously-interrupted rollback as a fresh job.
     * The original query is re-parsed and dispatched through the
     * queue. Idempotent: cells already rolled back skip with
     * "block changed", remaining cells apply.
     *
     * <p>Returns true on successful enqueue. The saved marker is
     * deleted only when the new job's normal terminal cleanup
     * fires.
     */
    public boolean resumeFromSaved(RollbackResumeStore.Saved saved, CommandSender sender) {
        QueryRequest request;
        try {
            RollbackMode mode = saved.mode() == RollbackJob.Mode.RESTORE
                    ? RollbackMode.RESTORE : RollbackMode.ROLLBACK;
            request = forceNoGroup(parser.parse(sender, saved.query(),
                    config.limits().rollbackResult()), mode);
        } catch (ParamParseException ex) {
            sender.sendMessage(Feedback.error(
                    "Resume failed: " + ex.getMessage() + " (query: " + saved.query() + ")"));
            return false;
        }
        // Treat the saved as a fresh job — new id, current sender
        // gets the progress messages even if the original operator
        // is offline. The resume marker for the OLD id is removed
        // separately by the rbqueue handler so re-run failures
        // don't lose the marker.
        UUID operatorId = sender instanceof Player p ? p.getUniqueId() : null;
        RollbackJob job = new RollbackJob(UUID.randomUUID(), operatorId, sender.getName(),
                saved.query(), saved.mode(), Instant.now(), sender);
        RollbackMode rmode = saved.mode() == RollbackJob.Mode.RESTORE
                ? RollbackMode.RESTORE : RollbackMode.ROLLBACK;
        // Pass the saved cursor + counters so the new job picks up
        // where the old one left off instead of re-running from the
        // start. The engine's "block changed" precondition still
        // skips already-applied cells, so it's idempotent — but
        // re-querying the whole record set on a 2M-block rollback
        // wastes a lot of ClickHouse work; the saved cursor jumps
        // past those records entirely.
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
        // Old marker drop — once the new job is queued / running we
        // no longer need the resume entry for the old id.
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
            request = forceNoGroup(parser.parse(sender, raw, config.limits().rollbackResult()), mode);
        } catch (ParamParseException ex) {
            sender.sendMessage(Feedback.error(ex.getMessage()));
            return;
        }

        // Wrap in a RollbackJob and submit to the queue. If no other
        // rollback is in flight, the queue immediately calls runJob;
        // otherwise the job waits its turn and the operator gets a
        // "queued at position N" message. Concurrency control means
        // two operators can't trip over each other writing the same
        // chunk through different rollbacks at once.
        UUID operatorId = sender instanceof Player p ? p.getUniqueId() : null;
        RollbackJob.Mode jobMode = mode == RollbackMode.RESTORE
                ? RollbackJob.Mode.RESTORE
                : RollbackJob.Mode.ROLLBACK;
        RollbackJob job = new RollbackJob(UUID.randomUUID(), operatorId, sender.getName(),
                raw, jobMode, Instant.now(), sender);
        pendingContexts.put(job.id, new JobContext(request, mode));

        // Persist BEFORE submitting to the queue. This way pending
        // jobs also have a marker — if the JVM dies while the job
        // is queued behind another, the marker survives and the
        // operator can /sg rbqueue resume it on restart. The marker
        // is updated with cursor + counters as pages complete in
        // streamPagesAndApply, and deleted when the job terminates.
        resumeStore.markStart(job.id, job.operatorName, job.operatorId, job.query, job.mode);

        int position = jobQueue.submit(job);
        if (position > 0) {
            sender.sendMessage(Feedback.bonus("Rollback queued at position " + position
                    + " (id " + job.shortId() + "). View / cancel with /sg rbqueue."));
        }
        // position == 0 → the queue's runner has already invoked
        // runJob, which sends the standard "Querying..." line.
    }

    /**
     * Queue runner callback — invoked by {@link RollbackJobQueue}
     * when a submitted job is ready to execute. Pulls the parsed
     * request, flushes the recorder, kicks off the streaming page
     * loop, and tells the queue when the job terminates so the next
     * one can dispatch.
     */
    public void runJob(RollbackJob job) {
        JobContext ctx = pendingContexts.remove(job.id);
        if (ctx == null) {
            // Defensive: shouldn't happen unless someone submits a
            // job without going through execute(). Drop it.
            jobQueue.finish(job, RollbackJob.State.FAILED);
            return;
        }
        job.sender.sendMessage(Feedback.querying());
        // Crash-resume marker: write at start, delete at end (any
        // terminal state). If the JVM dies mid-rollback, the file
        // persists and shows up in the next /sg rbqueue list as a
        // pending resume entry.
        resumeStore.markStart(job.id, job.operatorName, job.operatorId, job.query, job.mode);
        net.medievalrp.spyglass.api.util.Duration flushTimeout =
                config.limits().rollbackFlushTimeout();
        // Streaming rollback path. The recorder is flushed first so the
        // store has caught up to in-flight events (the read-your-writes
        // gap that caused "pixelated grain" rollbacks against slow
        // ClickHouse). Then we keyset-paginate through the store one
        // page at a time, applying each page through the per-tick
        // chunked engine before fetching the next. Memory is O(pageSize)
        // instead of O(matchSet).
        support.onAsyncThread(() -> {
            try {
                boolean drained = recorder.flush(flushTimeout);
                if (!drained) {
                    support.onMainThread(() -> job.sender.sendMessage(Feedback.bonus(
                            "Recorder still draining — rollback may miss the most recent events.")));
                }
                streamPagesAndApply(job, ctx.request(), ctx.mode(),
                        ctx.startCursor(), ctx.initialApplied(), ctx.initialSkipped());
                // Terminal state: cancelled wins over done if the
                // operator hit /sg rbqueue cancel mid-flight.
                jobQueue.finish(job, job.cancelFlag.get()
                        ? RollbackJob.State.CANCELLED
                        : RollbackJob.State.DONE);
                resumeStore.markFinish(job.id);
            } catch (Throwable thrown) {
                logger.warning("Spyglass rollback job " + job.shortId() + " failed: " + thrown);
                job.failureMessage = thrown.getMessage();
                jobQueue.finish(job, RollbackJob.State.FAILED);
                // Failed jobs leave the resume marker behind so
                // operators can decide whether to re-run.
            }
        });
    }

    /**
     * Page through the store with keyset pagination, applying each
     * page through {@link RollbackEngine#applyAllChunked}. Runs on the
     * async thread that called us — the apply step bounces work to the
     * main thread itself, and we block here on its completion future
     * before fetching the next page so memory never holds more than
     * one page's worth of effects.
     */
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
        int totalErrors = 0;  // Skipped with a real engine Error reason
        int totalSeen = 0;
        // Chunks touched across all pages — keyed (worldId, chunkX, chunkZ)
        // packed as a string. Reported in the summary so the operator
        // sees the spatial scope, not just block count.
        java.util.HashSet<String> chunkKeys = new java.util.HashSet<>();
        java.util.LinkedHashMap<String, Integer> skipCounts = new java.util.LinkedHashMap<>();
        // Inverses are accumulated for the undo stack push at the end.
        // For huge rollbacks this list can itself dominate heap (200B
        // per inverse × 1M = ~200 MB), so we cap it: if applied count
        // exceeds {@code rollback-undo-cap}, we drop further inverses
        // and tell the operator their undo isn't available. The
        // rollback itself still completes — only the undo is sacrificed.
        int undoCap = Math.max(0, config.limits().rollbackUndoCap());
        List<RollbackEffect> inverses = new ArrayList<>();
        boolean undoTruncated = false;

        net.medievalrp.spyglass.plugin.storage.QueryPage.Cursor cursor = startCursor;
        if (startCursor != null) {
            // Resumed mid-rollback. Tell the operator how far the
            // previous run got so they know whether to expect a
            // small or large continuation.
            support.onMainThread(() -> sender.sendMessage(Feedback.bonus(
                    "Resuming from cursor — " + initialApplied + " applied + "
                            + initialSkipped + " skipped before crash.")));
        }
        try {
            while (totalSeen < hardLimit) {
                // Cancellation: checked between pages so the current
                // page's per-chunk apply finishes (no half-page state)
                // but the next page is never fetched.
                if (cancelFlag.get()) {
                    break;
                }
                int remaining = hardLimit - totalSeen;
                int thisPage = Math.min(pageSize, remaining);
                net.medievalrp.spyglass.plugin.storage.QueryPage page;
                try {
                    page = store.queryPage(request, cursor, thisPage);
                } catch (RuntimeException storeFailure) {
                    logger.warning("Spyglass " + mode.label() + " query page failed: " + storeFailure);
                    final String msg = storeFailure.getMessage();
                    support.onMainThread(() -> sender.sendMessage(
                            Feedback.error(mode.label() + " failed: " + msg)));
                    return;
                }
                if (page.records().isEmpty()) {
                    break;
                }
                totalSeen += page.records().size();
                List<RollbackEffect> effects = collectEffectsFromRecords(page.records(), mode);
                if (effects.isEmpty()) {
                    cursor = page.next();
                    if (cursor == null) {
                        break;
                    }
                    continue;
                }
                // Pre-warm chunks for the page off-thread, then apply.
                // The apply future completes once every per-tick batch
                // in this page has run, so blocking here keeps heap
                // bounded — the next page can't be fetched until this
                // page's effects are released.
                preWarmChunksForRecords(page.records()).join();
                List<RollbackResult> results;
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
                // Tally and harvest inverses + count distinct chunks
                // touched (only Applied counts toward chunks).
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
                        // "Real" errors come through as RollbackReason.Error
                        // — distinct from BlockChanged (a normal "world
                        // moved on" skip) and InvalidLocation (chunk
                        // unloaded). Surfaced in the summary so the
                        // operator sees rollback failures separately
                        // from benign skips.
                        if (skipped.reason()
                                instanceof net.medievalrp.spyglass.api.rollback.RollbackReason.Error) {
                            totalErrors++;
                        }
                    }
                }
                // Update job's progress counters so /sg rbqueue can
                // show live status.
                job.appliedCount.set(totalApplied);
                job.skippedCount.set(totalSkipped);
                // Progress ping every page so the operator sees the
                // rollback isn't stuck on a long run.
                if (sender instanceof Player progressTarget) {
                    int appliedSoFar = totalApplied;
                    int skippedSoFar = totalSkipped;
                    support.onMainThread(() -> progressTarget.sendActionBar(
                            net.kyori.adventure.text.Component.text(
                                    "Rolling back: " + appliedSoFar + " applied, " + skippedSoFar + " skipped")));
                }
                cursor = page.next();
                // Checkpoint the cursor + counters in the resume
                // marker so a crash can resume from this exact point
                // instead of re-querying from the start. One file
                // write per page (≤200 writes for a 1M-block job),
                // <1 KB each — negligible overhead.
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
        }

        if (totalSeen == 0) {
            support.onMainThread(() -> sender.sendMessage(Feedback.error("No results.")));
            return;
        }

        // Bounce the summary delivery to the main thread for the chat
        // sends + the optional undo-stack push (the latter goes back to
        // async inside deliverStreamingSummary).
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
        Summary summary = new Summary(totalApplied, totalSkipped, totalErrors,
                chunkKeys.size(), elapsedMs, inverses);
        boolean truncated = undoTruncated;
        support.onMainThread(() -> deliverStreamingSummary(sender, mode, summary, skipCounts, truncated));
    }

    /**
     * Pre-warm chunks for a page of records via the Bukkit async chunk
     * loader. By the time the page's apply phase runs on the main
     * thread, the chunks are already resident — no chunk-load stall
     * inside the per-tick budget. Tolerates a missing Bukkit
     * environment for tests.
     */
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

    /** Pull a {@link BlockLocation} out of any RollbackEffect. All of
     *  the sealed subtypes carry one. Used for the chunk-touched count
     *  in the summary. */
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

    /**
     * Result of a streaming rollback / restore. Beyond the existing
     * applied + skipped + inverses, also surfaces:
     * <ul>
     *   <li>{@code chunks} — distinct chunks where Spyglass actually
     *       wrote at least one block. Tells the operator the spatial
     *       footprint of the op.</li>
     *   <li>{@code errors} — skipped effects whose reason was a real
     *       engine {@code Error}, not a benign "block changed" /
     *       "invalid location". Surfaced separately so a successful
     *       summary doesn't hide failures.</li>
     *   <li>{@code elapsedMs} — wall time from the first page query
     *       to the last page apply, as observed by the streaming
     *       loop. Doesn't include the undo-stack push at the end.</li>
     * </ul>
     */
    public record Summary(int applied, int skipped, int errors,
                          int chunks, long elapsedMs,
                          List<RollbackEffect> inverses) {

        public Summary {
            inverses = List.copyOf(inverses);
        }

        /** Back-compat ctor for callers that still build with just
         *  applied/skipped/inverses (e.g. {@link UndoService}). */
        public Summary(int applied, int skipped, List<RollbackEffect> inverses) {
            this(applied, skipped, 0, 0, 0L, inverses);
        }
    }
}
