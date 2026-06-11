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
                              int initialApplied, int initialSkipped,
                              @org.jetbrains.annotations.Nullable Runnable onDone) {
        JobContext(QueryRequest request, RollbackMode mode) {
            this(request, mode, null, 0, 0, null);
        }

        JobContext(QueryRequest request, RollbackMode mode,
                   @org.jetbrains.annotations.Nullable
                   net.medievalrp.spyglass.plugin.storage.QueryPage.Cursor startCursor,
                   int initialApplied, int initialSkipped) {
            this(request, mode, startCursor, initialApplied, initialSkipped, null);
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
        pendingContexts.put(job.id, new JobContext(replay, mode, null, 0, 0, onDone));
        resumeStore.markStart(job.id, job.operatorName, job.operatorId, job.query, job.mode);
        int position = jobQueue.submit(job);
        if (position > 0) {
            operator.sendMessage(Feedback.bonus("Undo queued at position " + position
                    + " (id " + job.shortId() + ")."));
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
        int windowCount = 0;
        java.util.HashSet<String> chunkKeys = new java.util.HashSet<>();
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
        // is the backpressure, so heap holds at most ~two windows of
        // slim effects regardless of the read window size:
        // rollback-page-size is now a query-efficiency dial with no
        // memory cost, and a 2M rollback pays ~16 window barriers
        // instead of one per page (measured 13.8s of barrier tax at
        // the old 20K default).
        final int windowSize = applyWindow;
        final Window readDone = new Window(List.of(), java.util.Map.of(), null, 0);
        final java.util.concurrent.ArrayBlockingQueue<Window> ready =
                new java.util.concurrent.ArrayBlockingQueue<>(2);
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
        reader.execute(() -> {
            WindowAccumulator acc = new WindowAccumulator(windowSize, mode, foldNanos);
            try {
                net.medievalrp.spyglass.plugin.storage.QueryPage.Cursor cur = readerStart;
                int seen = 0;
                while (!cancelFlag.get() && seen < hardLimit) {
                    int ask = Math.min(pageSize, hardLimit - seen);
                    int seenBefore = acc.seen();
                    cur = store.streamRollback(request, cur, ask, record -> {
                        acc.take(record);
                        if (acc.full()) {
                            putWindow(ready, acc.drain());
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
                if (window.effects().isEmpty()) {
                    checkpoint(job, window, totalApplied, totalSkipped);
                    continue;
                }
                long tPrewarm = System.nanoTime();
                preWarmChunks(window.prewarmChunks()).join();
                prewarmNanos += System.nanoTime() - tPrewarm;
                List<RollbackResult> results;
                long tApply = System.nanoTime();
                try {
                    java.util.concurrent.CompletableFuture<List<RollbackResult>> fut =
                            new java.util.concurrent.CompletableFuture<>();
                    List<RollbackEffect> effects = window.effects();
                    support.onMainThread(() ->
                            engine.applyAllChunked(effects, sender, support, batchSize, cancelFlag)
                                    .whenComplete((r, err) -> {
                                        if (err != null) fut.completeExceptionally(err);
                                        else fut.complete(r);
                                    }));
                    results = fut.join();
                } catch (java.util.concurrent.CompletionException ex) {
                    Throwable cause = ex.getCause() == null ? ex : ex.getCause();
                    logger.warning("Spyglass " + mode.label() + " window apply failed: " + cause);
                    final String msg = cause.getMessage() == null ? cause.toString() : cause.getMessage();
                    support.onMainThread(() -> sender.sendMessage(
                            Feedback.error(mode.label() + " failed: " + msg)));
                    return;
                }
                applyNanos += System.nanoTime() - tApply;
                for (RollbackResult r : results) {
                    if (r instanceof RollbackResult.Applied applied) {
                        totalApplied++;
                        BlockLocation loc = locationOf(applied.effect());
                        if (loc != null) {
                            chunkKeys.add(loc.worldId() + ":"
                                    + (loc.x() >> 4) + ":" + (loc.z() >> 4));
                            int[] box = worldBoxes.computeIfAbsent(loc.worldId(), k ->
                                    new int[]{loc.x(), loc.y(), loc.z(), loc.x(), loc.y(), loc.z()});
                            box[0] = Math.min(box[0], loc.x());
                            box[1] = Math.min(box[1], loc.y());
                            box[2] = Math.min(box[2], loc.z());
                            box[3] = Math.max(box[3], loc.x());
                            box[4] = Math.max(box[4], loc.y());
                            box[5] = Math.max(box[5], loc.z());
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
                checkpoint(job, window, totalApplied, totalSkipped);
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
        logger.info(String.format(
                "Spyglass %s %s timings: query=%dms collect=%dms prewarm=%dms apply=%dms"
                        + " | %d windows, %d chunks, %d applied, %dms total",
                mode.label(), job.shortId(),
                queryNanos / 1_000_000L, collectNanos / 1_000_000L,
                prewarmNanos / 1_000_000L, applyNanos / 1_000_000L,
                windowCount, chunkKeys.size(), totalApplied, elapsedMs));
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
        if (sender instanceof Player operator && totalApplied > 0) {
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
                                opSource, opLocation, config.server().name()),
                        mode.name(), reference));
            } catch (RuntimeException ex) {
                logger.warning("Spyglass rollback-op record emit failed ("
                        + mode.label() + " unaffected): " + ex.getMessage());
            }
        }
        Summary summary = new Summary(totalApplied, totalSkipped, totalErrors,
                chunkKeys.size(), elapsedMs);
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

    // Apply-window size: effects per engine dispatch (#19). At 131072
    // the window's backing array stays around 1 MB — far under G1's
    // humongous threshold at any common region size — while a 2M
    // rollback pays only ~16 window barriers instead of one per page.
    private int applyWindow = 131_072;

    // Visible for tests so the windowing can be exercised on small data.
    void applyWindowForTests(int windowSize) {
        this.applyWindow = Math.max(1, windowSize);
    }

    // One bounded unit of the streaming pipeline (#19): the effects to
    // apply, the chunks to pre-warm for them, the keyset position after
    // the last record folded in (for crash-resume checkpoints), and how
    // many records were consumed to build it.
    private record Window(List<RollbackEffect> effects,
                          Map<UUID, Set<Long>> prewarmChunks,
                          @org.jetbrains.annotations.Nullable
                          net.medievalrp.spyglass.plugin.storage.QueryPage.Cursor cursorAfter,
                          int seenDelta) {
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

    // Folds streamed records into windows on the reader thread: effect
    // direction by mode, prewarm chunk set, and the running keyset
    // position. Single-threaded by construction (one reader).
    private static final class WindowAccumulator {

        private final int windowSize;
        private final RollbackMode mode;
        private final java.util.concurrent.atomic.AtomicLong foldNanos;
        private List<RollbackEffect> effects;
        private Map<UUID, Set<Long>> prewarm;
        private java.time.Instant lastOccurred;
        private UUID lastId;
        private int seen;
        private int drainedSeen;

        WindowAccumulator(int windowSize, RollbackMode mode,
                          java.util.concurrent.atomic.AtomicLong foldNanos) {
            this.windowSize = windowSize;
            this.mode = mode;
            this.foldNanos = foldNanos;
            this.effects = new ArrayList<>();
            this.prewarm = new HashMap<>();
        }

        void take(EventRecord record) {
            long start = System.nanoTime();
            seen++;
            // Always advance the cursor, even for non-rollbackable
            // records — freezing on one would re-read it forever.
            lastOccurred = record.occurred();
            lastId = record.id();
            if (record instanceof Rollbackable rollbackable) {
                effects.add(mode == RollbackMode.ROLLBACK
                        ? rollbackable.rollbackEffect()
                        : rollbackable.restoreEffect());
                BlockLocation loc = record.location();
                if (loc != null) {
                    long packed = ((long) (loc.x() >> 4) << 32) | ((loc.z() >> 4) & 0xFFFFFFFFL);
                    prewarm.computeIfAbsent(loc.worldId(), k -> new HashSet<>()).add(packed);
                }
            }
            foldNanos.addAndGet(System.nanoTime() - start);
        }

        boolean full() {
            return effects.size() >= windowSize;
        }

        int seen() {
            return seen;
        }

        boolean hasUndrained() {
            return seen > drainedSeen;
        }

        Window drain() {
            Window window = new Window(effects, prewarm,
                    lastOccurred == null ? null
                            : new net.medievalrp.spyglass.plugin.storage.QueryPage.Cursor(
                                    lastOccurred, lastId),
                    seen - drainedSeen);
            drainedSeen = seen;
            effects = new ArrayList<>();
            prewarm = new HashMap<>();
            return window;
        }
    }

    // Checkpoint AFTER a window's records are applied so a crash
    // resumes past them; the force-overwrite apply makes any overlap
    // from a coarser-than-page checkpoint converge.
    private void checkpoint(RollbackJob job, Window window, int totalApplied, int totalSkipped) {
        RollbackResumeStore.Cursor resumeCursor = window.cursorAfter() == null ? null
                : new RollbackResumeStore.Cursor(
                        window.cursorAfter().occurred(), window.cursorAfter().id());
        resumeStore.markProgress(job.id, job.operatorName, job.operatorId,
                job.query, job.mode, job.submitTime,
                resumeCursor, totalApplied, totalSkipped);
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
                          int chunks, long elapsedMs) {

        public Summary(int applied, int skipped) {
            this(applied, skipped, 0, 0, 0L);
        }
    }
}
