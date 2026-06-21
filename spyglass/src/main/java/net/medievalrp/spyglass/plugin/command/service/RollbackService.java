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
import net.medievalrp.spyglass.api.param.ParamParseException;
import net.medievalrp.spyglass.api.query.Flag;
import net.medievalrp.spyglass.api.query.QueryRequest;
import net.medievalrp.spyglass.api.query.QueryResult;
import net.medievalrp.spyglass.api.query.Sort;
import net.medievalrp.spyglass.api.rollback.RollbackEffect;
import net.medievalrp.spyglass.api.rollback.RollbackResult;
import net.medievalrp.spyglass.plugin.command.param.QueryStringParser;
import net.medievalrp.spyglass.plugin.config.SpyglassConfig;
import net.medievalrp.spyglass.plugin.rollback.BlockColumns;
import net.medievalrp.spyglass.plugin.rollback.RollbackEngine;
import net.medievalrp.spyglass.plugin.rollback.UndoStack;
import net.medievalrp.spyglass.plugin.storage.UndoReferenceBson;
import net.medievalrp.spyglass.plugin.util.ChunkRelighter;
import net.medievalrp.spyglass.plugin.util.ChunkResender;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class RollbackService {

    // Fixed streaming read window (see usage in runJob). Formerly the
    // limits.rollback-page-size knob; since #19 it has no heap/GC effect
    // and is a query-efficiency default operators never need to tune.
    private static final int STREAM_PAGE_SIZE = 500_000;

    // Static so the stateless relight helper (shared with the legacy /undo
    // path) can log without an instance — keeps that path from interacting
    // with a RollbackService instance.
    private static final Logger RELIGHT_LOGGER = Logger.getLogger(RollbackService.class.getName());

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
    private final IpQueryResolver ipResolver;
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
                           RollbackResumeStore resumeStore,
                           IpQueryResolver ipResolver) {
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
        this.ipResolver = ipResolver;
    }

    public void wireQueue() {
        jobQueue.setRunner(this::runJob);
    }

    // Re-submit an interrupted rollback as a fresh job. Idempotent:
    // force-overwrite (#69) makes already-applied cells re-apply to the same
    // recorded state on the re-run, so an overlap from a coarse checkpoint
    // converges rather than erroring.
    //
    // Replays the marker's RESOLVED request verbatim (#49) — never
    // re-parse saved.query() here: r:/t: defaults would re-anchor to
    // THIS sender's position and the current clock, so a resume from
    // a different place (or hours later) would target a different
    // region and time window than the interrupted operation, and the
    // saved cursor would then page through the wrong match set.
    public boolean resumeFromSaved(RollbackResumeStore.Saved saved, CommandSender sender) {
        if (saved.requestBase64() == null) {
            // Pre-#49 marker, or an undo replay (whose label is not a
            // query and whose recovery path is /spyglass undo).
            sender.sendMessage(Feedback.error("Resume entry " + saved.shortId()
                    + " has no stored query plan (written by an older Spyglass,"
                    + " or left by an undo replay). Re-run the original command"
                    + " instead; discard the entry with /sg rbqueue cancel "
                    + saved.shortId() + "."));
            return false;
        }
        QueryRequest stored;
        try {
            stored = UndoReferenceBson.decodeBase64(saved.requestBase64()).request();
        } catch (RuntimeException ex) {
            sender.sendMessage(Feedback.error("Resume entry " + saved.shortId()
                    + " is unreadable: " + ex.getMessage() + ". Discard it with"
                    + " /sg rbqueue cancel " + saved.shortId() + "."));
            return false;
        }
        RollbackMode mode = saved.mode() == RollbackJob.Mode.RESTORE
                ? RollbackMode.RESTORE : RollbackMode.ROLLBACK;
        QueryRequest request = forceNoGroup(stored, mode);
        // Fresh job id so the current sender (which may differ from
        // the original operator) gets progress messages.
        UUID operatorId = sender instanceof Player p ? p.getUniqueId() : null;
        RollbackJob job = new RollbackJob(UUID.randomUUID(), operatorId, sender.getName(),
                saved.query(), saved.mode(), Instant.now(), sender);
        // Resume from the saved cursor so we don't re-query the
        // already-applied prefix on big rollbacks.
        net.medievalrp.spyglass.plugin.storage.QueryPage.Cursor startCursor = null;
        if (saved.cursor() != null) {
            startCursor = new net.medievalrp.spyglass.plugin.storage.QueryPage.Cursor(
                    saved.cursor().occurred(), saved.cursor().id());
        }
        pendingContexts.put(job.id, new JobContext(request, mode, startCursor,
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
                              int initialApplied, int initialSkipped,
                              @org.jetbrains.annotations.Nullable Runnable onDone,
                              boolean replay) {
        JobContext(QueryRequest request, RollbackMode mode) {
            this(request, mode, null, 0, 0, null, false);
        }

        JobContext(QueryRequest request, RollbackMode mode,
                   @org.jetbrains.annotations.Nullable
                   net.medievalrp.spyglass.plugin.storage.QueryPage.Cursor startCursor,
                   int initialApplied, int initialSkipped) {
            this(request, mode, startCursor, initialApplied, initialSkipped, null, false);
        }
    }

    // Replays an already-resolved request through the normal job
    // pipeline — same queue, progress UI, cancellation, and streaming
    // engine as an operator-issued rollback/restore. Used by /spyglass
    // undo to run a stored reference in the opposite direction; onDone
    // fires only on a clean completion (not cancel/failure), which is
    // when the undo reference may be consumed. Note: the queue label is
    // not re-parseable, so a crash mid-replay is not resumable via
    // /sg resume — the reference stays poppable and /undo just re-runs.
    public void executeReplay(Player operator, QueryRequest request, RollbackMode mode,
                              String queueLabel, Runnable onDone) {
        QueryRequest replay = forceNoGroup(request, mode);
        RollbackJob.Mode jobMode = mode == RollbackMode.RESTORE
                ? RollbackJob.Mode.RESTORE
                : RollbackJob.Mode.ROLLBACK;
        RollbackJob job = new RollbackJob(UUID.randomUUID(), operator.getUniqueId(),
                operator.getName(), queueLabel, jobMode, Instant.now(), operator);
        pendingContexts.put(job.id, new JobContext(replay, mode, null, 0, 0, onDone, true));
        // No stored request: a crashed replay's recovery path is
        // /spyglass undo (the reference stays poppable), not rbqueue
        // resume — the marker exists for visibility only.
        resumeStore.markStart(job.id, job.operatorName, job.operatorId, job.query, job.mode, null);
        int position = jobQueue.submit(job);
        if (position > 0) {
            operator.sendMessage(Feedback.bonus("Undo queued at position " + position
                    + " (id " + job.shortId() + ")."));
        }
    }

    public void execute(CommandSender sender, String raw, RollbackMode mode) {
        // Resolve any ip: addresses off-thread first; the continuation runs the
        // parse + queueing on the main thread. No ip: -> runs inline.
        ipResolver.resolve(raw, resolved -> executeResolved(sender, raw, mode, resolved));
    }

    private void executeResolved(CommandSender sender, String raw, RollbackMode mode,
                                 Map<String, List<UUID>> resolvedIps) {
        QueryRequest request;
        try {
            // No record cap: a rollback reverts everything its query matches,
            // bounded by limits.max-radius and the time window. The streaming
            // engine keeps heap flat regardless of size (#19), so a cap would
            // only ever silently half-restore a grief and mark the rest rolled
            // back. MAX_VALUE => the store query and apply loop never truncate.
            request = forceNoGroup(parser.parse(sender, raw, Integer.MAX_VALUE, resolvedIps), mode);
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
        resumeStore.markStart(job.id, job.operatorName, job.operatorId, job.query, job.mode,
                encodeResumeRequest(request, job.mode));

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
        resumeStore.markStart(job.id, job.operatorName, job.operatorId, job.query, job.mode,
                ctx.replay() ? null : encodeResumeRequest(ctx.request(), job.mode));
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
                        ctx.startCursor(), ctx.initialApplied(), ctx.initialSkipped(),
                        ctx.replay());
                // Cancellation wins over done if the operator hit
                // /sg rbqueue cancel mid-flight.
                jobQueue.finish(job, job.cancelFlag.get()
                        ? RollbackJob.State.CANCELLED
                        : RollbackJob.State.DONE);
                resumeStore.markFinish(job.id);
                if (!job.cancelFlag.get() && ctx.onDone() != null) {
                    ctx.onDone().run();
                }
            } catch (Throwable thrown) {
                logger.warning("Spyglass rollback job " + job.shortId() + " failed: " + thrown);
                job.failureMessage = thrown.getMessage();
                jobQueue.finish(job, RollbackJob.State.FAILED);
                // Leave the resume marker so the operator can re-run.
            }
        });
    }

    // Stream records off the store into bounded effect windows and
    // apply them through the chunked engine (#19). A reader thread
    // folds the wire stream into ≤applyWindow-effect windows; this
    // thread applies them as they land, with a two-slot queue as the
    // backpressure that keeps heap bounded regardless of page size.
    private void streamPagesAndApply(RollbackJob job, QueryRequest request, RollbackMode mode,
                                     @org.jetbrains.annotations.Nullable
                                     net.medievalrp.spyglass.plugin.storage.QueryPage.Cursor startCursor,
                                     int initialApplied, int initialSkipped,
                                     boolean replayOp) {
        CommandSender sender = job.sender;
        AtomicBoolean cancelFlag = job.cancelFlag;
        // Attribute any containers this rollback salvages to the operator and
        // group them under one rollback id; also resets per-run dedup (#76).
        engine.salvageBegin(sender == null ? "console" : sender.getName(),
                java.util.UUID.randomUUID());
        // Encoded once per job: checkpoint() re-writes the whole marker
        // after every applied window and must carry the same resolved
        // plan markStart wrote (#49). Null for replays — see runJob.
        String resumeRequest = replayOp ? null : encodeResumeRequest(request, job.mode);
        long startNanos = System.nanoTime();
        // Streaming read window (#19): how many records each keyset query
        // pulls off the wire before folding into a bounded apply window.
        // Pure query-efficiency with no heap/GC cost, so it is a fixed
        // internal default rather than an operator-facing knob.
        int pageSize = STREAM_PAGE_SIZE;
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
        int windowCount = 0;
        // Chunks already pre-warmed by an earlier window of this job. Doubles
        // as the distinct-chunk tally for the summary (every effect's chunk
        // lands here), so no separate string set is built per applied cell.
        Map<UUID, Set<Long>> warmedChunks = new HashMap<>();
        java.util.LinkedHashMap<String, Integer> skipCounts = new java.util.LinkedHashMap<>();
        // Per-world bounding boxes of applied writes — recorded into the
        // operation reference so search synthesis (#22) and operators
        // know where the op landed. {minX,minY,minZ,maxX,maxY,maxZ}.
        java.util.LinkedHashMap<UUID, int[]> worldBoxes = new java.util.LinkedHashMap<>();
        // Undo capture is BY REFERENCE (#17): one small row written at
        // completion records the resolved query + a time ceiling, and
        // /spyglass undo replays the same record set in the opposite
        // direction. Nothing is captured per effect.

        if (startCursor != null) {
            support.onMainThread(() -> sender.sendMessage(Feedback.bonus(
                    "Resuming from cursor: " + initialApplied + " applied + "
                            + initialSkipped + " skipped before crash.")));
        }
        // Streaming pipeline (#19): a reader thread streams records off
        // the wire and folds each one straight into a bounded effect
        // window; this thread consumes windows — prewarm, apply,
        // account — while the reader keeps reading. The two-slot queue
        // is the backpressure, so only a handful of short-lived windows
        // are ever in flight regardless of the read window size:
        // rollback-page-size is now a query-efficiency dial with no
        // memory or GC cost, and the barriers are queue handoffs, not
        // the per-page query+collect+prewarm stalls that cost 13.8s at
        // the old 20K default.
        final int windowSize = applyWindow;
        final Window readDone = new Window(
                java.util.Map.of(), List.of(), java.util.Map.of(), java.util.Map.of(), null, 0);
        // One slot, not two: a deeper queue only grows the in-flight
        // live set that MTT=1 promotes (see applyWindow). One queued +
        // one applying + one accumulating still overlaps read with
        // apply fully.
        final java.util.concurrent.ArrayBlockingQueue<Window> ready =
                new java.util.concurrent.ArrayBlockingQueue<>(1);
        final java.util.concurrent.atomic.AtomicReference<Throwable> readerFailure =
                new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.atomic.AtomicLong foldNanos =
                new java.util.concurrent.atomic.AtomicLong();
        java.util.concurrent.ExecutorService reader =
                java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "Spyglass-RollbackRead");
                    t.setDaemon(true);
                    return t;
                });
        final net.medievalrp.spyglass.plugin.storage.QueryPage.Cursor readerStart = startCursor;
        final boolean rollbackDirection = mode == RollbackMode.ROLLBACK;
        reader.execute(() -> {
            WindowAccumulator acc = new WindowAccumulator(windowSize, foldNanos);
            try {
                net.medievalrp.spyglass.plugin.storage.QueryPage.Cursor cur = readerStart;
                int seen = 0;
                while (!cancelFlag.get() && seen < hardLimit) {
                    int ask = Math.min(pageSize, hardLimit - seen);
                    int seenBefore = acc.seen();
                    // Lean effect stream (#67): the store folds each row
                    // straight into the columnar accumulator in the requested
                    // direction. A simple block-replace arrives as primitives
                    // (no effect/snapshot object); only tile-entity blocks,
                    // containers, and entities arrive as built effects.
                    cur = store.streamRollbackEffects(request, cur, ask, rollbackDirection,
                            new net.medievalrp.spyglass.plugin.storage.RecordStore.RollbackEffectSink() {
                                @Override
                                public void block(UUID worldId, int x, int y, int z, String blockData,
                                                  String expectedData, Instant occurred, UUID id) {
                                    acc.block(worldId, x, y, z, blockData, expectedData, occurred, id);
                                    if (acc.full()) {
                                        putWindow(ready, acc.drain());
                                    }
                                }

                                @Override
                                public void complex(RollbackEffect effect, Instant occurred, UUID id) {
                                    acc.complex(effect, occurred, id);
                                    if (acc.full()) {
                                        putWindow(ready, acc.drain());
                                    }
                                }

                                @Override
                                public void skip(Instant occurred, UUID id) {
                                    acc.skip(occurred, id);
                                }
                            });
                    int got = acc.seen() - seenBefore;
                    seen += got;
                    if (cur == null || got < ask) {
                        break; // result set exhausted
                    }
                }
                if (acc.hasUndrained()) {
                    putWindow(ready, acc.drain());
                }
                putWindow(ready, readDone);
            } catch (ReadAborted aborted) {
                // Consumer tore the pipeline down (cancel/failure);
                // nothing to report from this side.
            } catch (Throwable thrown) {
                readerFailure.set(thrown);
                ready.clear();
                ready.offer(readDone);
            }
        });
        try {
            while (true) {
                // Cancellation is checked between windows so the
                // current window finishes cleanly.
                if (cancelFlag.get()) {
                    break;
                }
                Window window;
                long tQuery = System.nanoTime();
                try {
                    window = ready.take();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                // Measures only the *wait* for the reader — ~0 once the
                // pipeline is warm, because folding happened during the
                // previous window's apply.
                queryNanos += System.nanoTime() - tQuery;
                if (window == readDone) {
                    break;
                }
                windowCount++;
                totalSeen += window.seenDelta();
                if (window.isEmpty()) {
                    checkpoint(job, window, totalApplied, totalSkipped, resumeRequest);
                    continue;
                }
                // Only join on chunks this job hasn't warmed yet — the
                // join is tick-aligned (getChunkAtAsync resolves on the
                // main thread), so a redundant one stalls the window by
                // up to a tick. Most windows after the first few skip
                // it entirely.
                Map<UUID, Set<Long>> freshChunks = new HashMap<>();
                for (Map.Entry<UUID, Set<Long>> entry : window.prewarmChunks().entrySet()) {
                    Set<Long> seenChunks = warmedChunks.computeIfAbsent(
                            entry.getKey(), k -> new HashSet<>());
                    Set<Long> fresh = new HashSet<>();
                    for (Long packed : entry.getValue()) {
                        if (seenChunks.add(packed)) {
                            fresh.add(packed);
                        }
                    }
                    if (!fresh.isEmpty()) {
                        freshChunks.put(entry.getKey(), fresh);
                    }
                }
                if (!freshChunks.isEmpty()) {
                    long tPrewarm = System.nanoTime();
                    preWarmChunks(freshChunks).join();
                    prewarmNanos += System.nanoTime() - tPrewarm;
                }
                // Apply: simple block-replaces via the columnar engine path
                // (counts only, no per-cell objects — the apply-side fix that
                // stops old-gen promotion under MTT=1), the rare complex
                // effects via the object path. Both run their writes off-main
                // and yield by tick, so TPS stays at ~20.
                long tApply = System.nanoTime();
                RollbackEngine.ApplyCounts counts = new RollbackEngine.ApplyCounts();
                List<RollbackResult> complexResults = List.of();
                try {
                    for (Map.Entry<UUID, BlockColumns> worldCols : window.columnsByWorld().entrySet()) {
                        BlockColumns cols = worldCols.getValue();
                        if (cols.count() == 0) {
                            continue;
                        }
                        UUID worldId = worldCols.getKey();
                        java.util.concurrent.CompletableFuture<RollbackEngine.ApplyCounts> fut =
                                new java.util.concurrent.CompletableFuture<>();
                        support.onMainThread(() ->
                                engine.applyColumnsChunked(worldId, cols, sender, support, batchSize, cancelFlag)
                                        .whenComplete((c, err) -> {
                                            if (err != null) fut.completeExceptionally(err);
                                            else fut.complete(c);
                                        }));
                        counts.add(fut.join());
                    }
                    if (!window.complex().isEmpty()) {
                        java.util.concurrent.CompletableFuture<List<RollbackResult>> fut =
                                new java.util.concurrent.CompletableFuture<>();
                        List<RollbackEffect> complex = window.complex();
                        support.onMainThread(() ->
                                engine.applyAllChunked(complex, sender, support, batchSize, cancelFlag)
                                        .whenComplete((r, err) -> {
                                            if (err != null) fut.completeExceptionally(err);
                                            else fut.complete(r);
                                        }));
                        complexResults = fut.join();
                    }
                } catch (java.util.concurrent.CompletionException ex) {
                    Throwable cause = ex.getCause() == null ? ex : ex.getCause();
                    logger.warning("Spyglass " + mode.label() + " window apply failed: " + cause);
                    final String msg = cause.getMessage() == null ? cause.toString() : cause.getMessage();
                    support.onMainThread(() -> sender.sendMessage(
                            Feedback.error(mode.label() + " failed: " + msg)));
                    return;
                }
                applyNanos += System.nanoTime() - tApply;
                // Columnar block-replace counts.
                totalApplied += (int) counts.applied;
                totalSkipped += (int) counts.skipped();
                totalErrors += (int) counts.errors();
                mergeSkip(skipCounts, "block changed", counts.blockChanged);
                mergeSkip(skipCounts, "Unparseable blockdata", counts.unparseable);
                mergeSkip(skipCounts, "invalid location", counts.invalidLocation);
                mergeSkip(skipCounts, "Cancelled by operator", counts.cancelled);
                // The rare complex effects' per-cell results.
                for (RollbackResult r : complexResults) {
                    if (r instanceof RollbackResult.Applied) {
                        totalApplied++;
                    } else if (r instanceof RollbackResult.Skipped skipped) {
                        totalSkipped++;
                        skipCounts.merge(skipped.reason().message(), 1, Integer::sum);
                        // Only RollbackReason.Error is a real failure; every
                        // other skip reason (invalid location, not-supported,
                        // missing data) is benign.
                        if (skipped.reason()
                                instanceof net.medievalrp.spyglass.api.rollback.RollbackReason.Error) {
                            totalErrors++;
                        }
                    }
                }
                // Per-world bounding box of this window (block + complex). A
                // superset of the applied box — safe: the op reference is only
                // emitted when applied > 0, and synthesis (#22) re-filters
                // within it.
                for (Map.Entry<UUID, int[]> boxEntry : window.boxes().entrySet()) {
                    int[] wb = boxEntry.getValue();
                    worldBoxes.merge(boxEntry.getKey(),
                            new int[]{wb[0], wb[1], wb[2], wb[3], wb[4], wb[5]},
                            (a, b) -> {
                                a[0] = Math.min(a[0], b[0]);
                                a[1] = Math.min(a[1], b[1]);
                                a[2] = Math.min(a[2], b[2]);
                                a[3] = Math.max(a[3], b[3]);
                                a[4] = Math.max(a[4], b[4]);
                                a[5] = Math.max(a[5], b[5]);
                                return a;
                            });
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
                checkpoint(job, window, totalApplied, totalSkipped, resumeRequest);
            }
            Throwable readFailed = readerFailure.get();
            if (readFailed != null) {
                logger.warning("Spyglass " + mode.label() + " stream read failed: " + readFailed);
                final String msg = readFailed.getMessage() == null
                        ? readFailed.toString() : readFailed.getMessage();
                support.onMainThread(() -> sender.sendMessage(
                        Feedback.error(mode.label() + " failed: " + msg)));
                return;
            }
        } catch (RuntimeException unexpected) {
            logger.warning("Spyglass " + mode.label() + " streaming failure: " + unexpected);
            final String msg = unexpected.getMessage() == null ? unexpected.toString() : unexpected.getMessage();
            support.onMainThread(() -> sender.sendMessage(
                    Feedback.error(mode.label() + " failed: " + msg)));
            return;
        } finally {
            // Tear down the reader on every exit path (normal,
            // early-return, or exception); shutdownNow interrupts a
            // blocked put or an in-flight wire read.
            reader.shutdownNow();
        }

        if (totalSeen == 0) {
            support.onMainThread(() -> sender.sendMessage(Feedback.error("No results.")));
            return;
        }

        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
        // Folding happens on the reader thread; pull its tally into the
        // collect slot so the breakdown still accounts for it.
        collectNanos = foldNanos.get();
        // Distinct chunks touched == every chunk that was pre-warmed.
        int chunkCount = 0;
        for (Set<Long> set : warmedChunks.values()) {
            chunkCount += set.size();
        }
        // The engine restores blocks with a direct LevelChunkSection
        // palette write that skips the light engine (ChunkDirectWriter), so
        // the rolled region renders with stale, pre-rollback lighting until
        // a natural relight. Recompute it now over exactly the chunks we
        // wrote (Starlight, off-main; resend each chunk as it lands). Covers
        // rollback, restore, and reference-based /undo (executeReplay).
        if (totalApplied > 0) {
            relightWrittenChunks(support, warmedChunks);
        }
        logger.info(String.format(
                "Spyglass %s %s timings: query=%dms collect=%dms prewarm=%dms apply=%dms"
                        + " | %d windows, %d chunks, %d applied, %dms total",
                mode.label(), job.shortId(),
                queryNanos / 1_000_000L, collectNanos / 1_000_000L,
                prewarmNanos / 1_000_000L, applyNanos / 1_000_000L,
                windowCount, chunkCount, totalApplied, elapsedMs));
        // One reference blob serves both audiences: the undo ledger row
        // (written before the summary so "done" implies /undo is ready)
        // and the rollback-op event record that searches synthesize the
        // per-block rolled-* entries from (#22). The cancel path also
        // lands here, and a partial application is itself a valid
        // replay target (force-overwrite converges).
        java.util.List<net.medievalrp.spyglass.plugin.storage.UndoReferenceBson.WorldBox> boxes =
                worldBoxes.entrySet().stream()
                        .map(e -> new net.medievalrp.spyglass.plugin.storage.UndoReferenceBson.WorldBox(
                                e.getKey(), e.getValue()[0], e.getValue()[1], e.getValue()[2],
                                e.getValue()[3], e.getValue()[4], e.getValue()[5]))
                        .toList();
        String reference = net.medievalrp.spyglass.plugin.storage.UndoReferenceBson.encodeBase64(
                request, mode.name(), job.submitTime, boxes, totalApplied, totalSkipped);
        boolean undoUnavailable = false;
        // Replay ops (undo) do NOT push a reference: the popped reference
        // is consumed on clean completion, so repeated /undo unwinds the
        // per-operator stack oldest-ward instead of ping-ponging on the
        // newest operation (#31). Redoing an undone op is /sg restore of
        // the same query.
        if (!replayOp && sender instanceof Player operator && totalApplied > 0) {
            try {
                undoStack.pushReference(operator.getUniqueId(), mode.name(), reference);
            } catch (RuntimeException ex) {
                logger.warning("Spyglass undo reference push failed (" + mode.label()
                        + " applied; /undo unavailable): " + ex.getMessage());
                undoUnavailable = true;
            }
        }
        // Only in synthesized mode: receipts mode persists the per-block
        // trail itself, and emitting op records there too would make a
        // later mode flip double-render those operations (receipt rows
        // plus synthesis from the same op).
        if (totalApplied > 0 && config.storage().rolledAuditSynthesized()) {
            try {
                java.time.Instant opOccurred = java.time.Instant.now();
                BlockLocation opLocation = boxes.isEmpty()
                        ? new BlockLocation(new UUID(0L, 0L), "", 0, 0, 0)
                        : new BlockLocation(boxes.get(0).worldId(), "",
                                boxes.get(0).minX(), boxes.get(0).minY(), boxes.get(0).minZ());
                net.medievalrp.spyglass.api.event.Source opSource = sender instanceof Player p
                        ? net.medievalrp.spyglass.api.event.Source.player(p.getUniqueId(), p.getName())
                        : net.medievalrp.spyglass.api.event.Source.environment(sender.getName());
                recorder.record(net.medievalrp.spyglass.api.event.RollbackOpRecord.of(
                        new net.medievalrp.spyglass.api.event.RecordContext(
                                net.medievalrp.spyglass.api.util.EventIds.newId(), opOccurred,
                                config.storage().retention().after(opOccurred),
                                net.medievalrp.spyglass.api.event.Origin.rollback(sender.getName()),
                                opSource, opLocation, config.server().name(), java.util.Map.of()),
                        mode.name(), reference));
            } catch (RuntimeException ex) {
                logger.warning("Spyglass rollback-op record emit failed ("
                        + mode.label() + " unaffected): " + ex.getMessage());
            }
        }
        Summary summary = new Summary(totalApplied, totalSkipped, totalErrors,
                chunkCount, elapsedMs);
        boolean undoUnavailableFinal = undoUnavailable;
        support.onMainThread(() -> deliverStreamingSummary(
                sender, mode, summary, skipCounts, undoUnavailableFinal));
    }

    // Pre-warm chunks via getChunkAtAsync so the apply phase doesn't
    // pay a chunk-load stall inside its tick budget. Takes the packed
    // (cx, cz) set the window accumulator built record-by-record.
    // No-op outside a live Bukkit server (tests).
    private CompletableFuture<Void> preWarmChunks(Map<UUID, Set<Long>> byWorld) {
        try {
            if (byWorld.isEmpty() || Bukkit.getServer() == null) {
                return CompletableFuture.completedFuture(null);
            }
            List<CompletableFuture<?>> futures = new ArrayList<>();
            for (Map.Entry<UUID, Set<Long>> entry : byWorld.entrySet()) {
                World world = Bukkit.getWorld(entry.getKey());
                if (world == null) {
                    continue;
                }
                for (long packed : entry.getValue()) {
                    int cx = (int) (packed >> 32);
                    int cz = (int) packed;
                    futures.add(world.getChunkAtAsync(cx, cz));
                }
            }
            if (futures.isEmpty()) {
                return CompletableFuture.completedFuture(null);
            }
            return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
        } catch (Throwable thrown) {
            return CompletableFuture.completedFuture(null);
        }
    }


    private void deliverStreamingSummary(CommandSender sender, RollbackMode mode,
                                         Summary summary,
                                         java.util.LinkedHashMap<String, Integer> skipCounts,
                                         boolean undoUnavailable) {
        for (var entry : skipCounts.entrySet()) {
            String suffix = entry.getValue() == 1 ? "" : " ×" + entry.getValue();
            sender.sendMessage(Feedback.bonus("Skip Reason: " + entry.getKey() + suffix));
        }
        sender.sendMessage(summaryLine(summary));
        if (undoUnavailable) {
            sender.sendMessage(Feedback.bonus(
                    "Undo reference could not be saved; /spyglass undo cannot reverse this op."));
        }
    }

    // Apply-window size: effects per engine dispatch (#19). Two costs
    // pull in opposite directions (both measured 2026-06-10):
    //  - per-window tax: each window pays tick-aligned scheduling for
    //    the prewarm join + main-thread apply dispatch (~2-3 ticks).
    //    16K windows = 123 of them = a 25 s rollback that loses to
    //    CoreProtect; 131K = 16 = ~2 s hidden inside a 7.6 s run.
    //  - GC live set: customers run stock Aikar flags
    //    (MaxTenuringThreshold=1), so effects alive across one young
    //    GC promote to old gen wholesale. The fix for that is making
    //    the in-flight bytes small (snapshot interning in the fold,
    //    single-slot queue), NOT making windows short.
    // 131072 keeps the backing array (~1 MB) far under the humongous
    // threshold at every common region size.
    private int applyWindow = 131_072;

    // Visible for tests so the windowing can be exercised on small data.
    void applyWindowForTests(int windowSize) {
        this.applyWindow = Math.max(1, windowSize);
    }

    // One bounded unit of the streaming pipeline (#19, #67): simple
    // block-replaces as columnar primitives (per world), the rare complex
    // effects as objects, the chunks to pre-warm, the per-world bounding
    // box, the keyset position after the last row folded in (for
    // crash-resume checkpoints), and how many rows were consumed to build it.
    private record Window(Map<UUID, BlockColumns> columnsByWorld,
                          List<RollbackEffect> complex,
                          Map<UUID, Set<Long>> prewarmChunks,
                          Map<UUID, int[]> boxes,
                          @org.jetbrains.annotations.Nullable
                          net.medievalrp.spyglass.plugin.storage.QueryPage.Cursor cursorAfter,
                          int seenDelta) {
        boolean isEmpty() {
            if (!complex.isEmpty()) {
                return false;
            }
            for (BlockColumns cols : columnsByWorld.values()) {
                if (cols.count() > 0) {
                    return false;
                }
            }
            return true;
        }
    }

    // Unwinds the reader when the consumer tore the pipeline down.
    private static final class ReadAborted extends RuntimeException {
        ReadAborted(InterruptedException cause) {
            super(cause);
        }
    }

    private static void putWindow(java.util.concurrent.BlockingQueue<Window> queue, Window window) {
        try {
            queue.put(window);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new ReadAborted(ie);
        }
    }

    // Folds streamed rows into windows on the reader thread (#67): simple
    // block-replaces into primitive BlockColumns (per world, with an
    // interned block-data palette), complex effects into an object list,
    // plus the prewarm chunk set, per-world box, and keyset position.
    // Single-threaded by construction (one reader).
    private static final class WindowAccumulator {

        // Snapshot canonicalizer for the COMPLEX path only: those effects
        // outlive the row while their window queues for apply, and under
        // stock Aikar flags (MTT=1) survivors promote to old gen. Simple
        // blocks never reach here — they are primitives in BlockColumns,
        // and their block-data is interned in the column palette instead.
        private static final int INTERN_CAP = 4096;
        private final Map<net.medievalrp.spyglass.api.event.BlockSnapshot,
                net.medievalrp.spyglass.api.event.BlockSnapshot> snapshotCache = new HashMap<>();

        private final int windowSize;
        private final int columnInitialCapacity;
        private final java.util.concurrent.atomic.AtomicLong foldNanos;
        private Map<UUID, BlockColumns> columnsByWorld;
        private List<RollbackEffect> complex;
        private Map<UUID, Set<Long>> prewarm;
        private Map<UUID, int[]> boxes;
        private java.time.Instant lastOccurred;
        private UUID lastId;
        private int effectCount;
        private int seen;
        private int drainedSeen;

        WindowAccumulator(int windowSize,
                          java.util.concurrent.atomic.AtomicLong foldNanos) {
            this.windowSize = windowSize;
            // Single-world is the norm, so the first world's columns grow to
            // ~windowSize; cap the initial allocation so a rare many-world
            // window doesn't over-reserve per world.
            this.columnInitialCapacity = Math.min(Math.max(windowSize, 16), 16_384);
            this.foldNanos = foldNanos;
            reset();
        }

        private void reset() {
            this.columnsByWorld = new HashMap<>();
            this.complex = new ArrayList<>();
            this.prewarm = new HashMap<>();
            this.boxes = new HashMap<>();
            this.effectCount = 0;
        }

        // Simple block-replace: folded straight into primitive columns,
        // block-data interned to a palette id — no effect/snapshot object.
        void block(UUID worldId, int x, int y, int z, String blockData,
                   String expectedData, java.time.Instant occurred, UUID id) {
            long start = System.nanoTime();
            seen++;
            effectCount++;
            lastOccurred = occurred;
            lastId = id;
            BlockColumns cols = columnsByWorld.computeIfAbsent(
                    worldId, k -> new BlockColumns(columnInitialCapacity));
            cols.add(x, y, z, cols.intern(blockData), cols.intern(expectedData));
            recordChunkAndBox(worldId, x, y, z);
            foldNanos.addAndGet(System.nanoTime() - start);
        }

        // Container / entity / tile-entity block / custom: kept as an
        // object and applied via the proven object path.
        void complex(RollbackEffect effect, java.time.Instant occurred, UUID id) {
            long start = System.nanoTime();
            seen++;
            effectCount++;
            lastOccurred = occurred;
            lastId = id;
            complex.add(intern(effect));
            BlockLocation loc = locationOf(effect);
            if (loc != null) {
                recordChunkAndBox(loc.worldId(), loc.x(), loc.y(), loc.z());
            }
            foldNanos.addAndGet(System.nanoTime() - start);
        }

        // A matched-but-not-rollbackable row: advance the cursor only.
        // Freezing on one would re-read it forever.
        void skip(java.time.Instant occurred, UUID id) {
            seen++;
            lastOccurred = occurred;
            lastId = id;
        }

        private void recordChunkAndBox(UUID worldId, int x, int y, int z) {
            long packed = ((long) (x >> 4) << 32) | ((z >> 4) & 0xFFFFFFFFL);
            prewarm.computeIfAbsent(worldId, k -> new HashSet<>()).add(packed);
            int[] box = boxes.computeIfAbsent(worldId, k -> new int[]{x, y, z, x, y, z});
            if (x < box[0]) box[0] = x;
            if (y < box[1]) box[1] = y;
            if (z < box[2]) box[2] = z;
            if (x > box[3]) box[3] = x;
            if (y > box[4]) box[4] = y;
            if (z > box[5]) box[5] = z;
        }

        private RollbackEffect intern(RollbackEffect effect) {
            if (!(effect instanceof RollbackEffect.BlockReplace br)) {
                return effect;
            }
            var expected = intern(br.expectedCurrent());
            var replacement = intern(br.replacement());
            if (expected == br.expectedCurrent() && replacement == br.replacement()) {
                return effect;
            }
            return new RollbackEffect.BlockReplace(br.location(), expected, replacement);
        }

        private net.medievalrp.spyglass.api.event.BlockSnapshot intern(
                net.medievalrp.spyglass.api.event.BlockSnapshot snapshot) {
            if (snapshot == null) {
                return null;
            }
            if (snapshotCache.size() >= INTERN_CAP) {
                snapshotCache.clear();
            }
            return snapshotCache.computeIfAbsent(snapshot, s -> s);
        }

        boolean full() {
            return effectCount >= windowSize;
        }

        int seen() {
            return seen;
        }

        boolean hasUndrained() {
            return seen > drainedSeen;
        }

        Window drain() {
            Window window = new Window(columnsByWorld, complex, prewarm, boxes,
                    lastOccurred == null ? null
                            : new net.medievalrp.spyglass.plugin.storage.QueryPage.Cursor(
                                    lastOccurred, lastId),
                    seen - drainedSeen);
            drainedSeen = seen;
            reset();
            return window;
        }
    }

    private static void mergeSkip(java.util.LinkedHashMap<String, Integer> skipCounts,
                                  String reason, long count) {
        if (count > 0) {
            skipCounts.merge(reason, (int) count, Integer::sum);
        }
    }

    // Checkpoint AFTER a window's records are applied so a crash
    // resumes past them; the force-overwrite apply makes any overlap
    // from a coarser-than-page checkpoint converge.
    private void checkpoint(RollbackJob job, Window window, int totalApplied, int totalSkipped,
                            @org.jetbrains.annotations.Nullable String requestBase64) {
        RollbackResumeStore.Cursor resumeCursor = window.cursorAfter() == null ? null
                : new RollbackResumeStore.Cursor(
                        window.cursorAfter().occurred(), window.cursorAfter().id());
        resumeStore.markProgress(job.id, job.operatorName, job.operatorId,
                job.query, job.mode, requestBase64, job.submitTime,
                resumeCursor, totalApplied, totalSkipped);
    }

    /**
     * The resume marker stores the RESOLVED request (#49) — the same
     * rule {@link UndoReferenceBson} documents for undo references:
     * relative params are already anchored, so replaying the plan can
     * never re-anchor to the resumer's position or clock. The mode
     * and ceiling ride along for the blob format; resume reads only
     * the request.
     */
    private static String encodeResumeRequest(QueryRequest request, RollbackJob.Mode mode) {
        return UndoReferenceBson.encodeBase64(request, mode.name(), Instant.now());
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

    // Recompute + resend lighting for chunks a rollback/undo restored via
    // the engine's direct section-palette write (which skips the light
    // engine). Shared by the windowed apply path and the legacy /undo
    // replay. Best-effort and fully off the hot path: the Starlight
    // recompute runs off-main and each chunk is resent with fresh light as
    // it lands. No-ops cleanly where the relight API is unavailable.
    static void relightWrittenChunks(ServiceSupport support, Map<UUID, Set<Long>> chunksByWorld) {
        try {
            for (Map.Entry<UUID, Set<Long>> entry : chunksByWorld.entrySet()) {
                World world = Bukkit.getWorld(entry.getKey());
                Set<Long> chunks = entry.getValue();
                if (world == null || chunks.isEmpty()) {
                    continue;
                }
                long[] keys = new long[chunks.size()];
                int i = 0;
                for (Long key : chunks) {
                    keys[i++] = key;
                }
                support.onMainThread(() -> ChunkRelighter.relight(world, keys,
                        (cx, cz) -> support.onMainThread(() -> ChunkResender.resend(world, cx, cz))));
            }
        } catch (Throwable t) {
            // Relight is best-effort: a lighting-refresh hiccup must never
            // abort the rollback's completion path. ChunkRelighter logs its
            // own NMS failures; this guards the surrounding scheduling (and
            // keeps headless tests, where Bukkit has no server, green).
            RELIGHT_LOGGER.log(java.util.logging.Level.FINE,
                    "Spyglass post-rollback relight skipped", t);
        }
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
                          int chunks, long elapsedMs) {

        public Summary(int applied, int skipped) {
            this(applied, skipped, 0, 0, 0L);
        }
    }
}
