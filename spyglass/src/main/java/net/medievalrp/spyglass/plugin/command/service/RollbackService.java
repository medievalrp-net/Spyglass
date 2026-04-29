package net.medievalrp.spyglass.plugin.command.service;

import net.medievalrp.spyglass.plugin.command.render.Feedback;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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

    public RollbackService(SpyglassApi api,
                           QueryStringParser parser,
                           SpyglassConfig config,
                           RollbackEngine engine,
                           UndoStack undoStack,
                           ServiceSupport support,
                           net.medievalrp.spyglass.plugin.pipeline.Recorder recorder,
                           net.medievalrp.spyglass.plugin.storage.RecordStore store,
                           Logger logger) {
        this.api = api;
        this.parser = parser;
        this.config = config;
        this.engine = engine;
        this.undoStack = undoStack;
        this.support = support;
        this.recorder = recorder;
        this.store = store;
        this.logger = logger;
    }

    public void execute(CommandSender sender, String raw, RollbackMode mode) {
        QueryRequest request;
        try {
            request = forceNoGroup(parser.parse(sender, raw, config.limits().rollbackResult()), mode);
        } catch (ParamParseException ex) {
            sender.sendMessage(Feedback.error(ex.getMessage()));
            return;
        }
        sender.sendMessage(Feedback.querying());
        // Streaming rollback path. The recorder is flushed first so the
        // store has caught up to in-flight events (the read-your-writes
        // gap that caused "pixelated grain" rollbacks against slow
        // ClickHouse). Then we keyset-paginate through the store one
        // page at a time, applying each page through the per-tick
        // chunked engine before fetching the next. Memory is O(pageSize)
        // instead of O(matchSet) — a 1M-row rollback that previously
        // OOM'd on a 3 GB heap now runs as 200 pages of 5k effects
        // each, with the heap holding only one page at a time.
        net.medievalrp.spyglass.api.util.Duration flushTimeout =
                config.limits().rollbackFlushTimeout();
        support.onAsyncThread(() -> {
            boolean drained = recorder.flush(flushTimeout);
            if (!drained) {
                support.onMainThread(() -> sender.sendMessage(Feedback.bonus(
                        "Recorder still draining — rollback may miss the most recent events.")));
            }
            streamPagesAndApply(sender, request, mode);
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
    private void streamPagesAndApply(CommandSender sender, QueryRequest request, RollbackMode mode) {
        int pageSize = Math.max(100, config.limits().rollbackPageSize());
        int batchSize = config.limits().rollbackBatchSize();
        int hardLimit = request.limit();
        int totalApplied = 0;
        int totalSkipped = 0;
        int totalSeen = 0;
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

        net.medievalrp.spyglass.plugin.storage.QueryPage.Cursor cursor = null;
        try {
            while (totalSeen < hardLimit) {
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
                            engine.applyAllChunked(effects, sender, support, batchSize)
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
                // Tally and harvest inverses.
                for (RollbackResult r : results) {
                    if (r instanceof RollbackResult.Applied applied) {
                        totalApplied++;
                        if (totalApplied <= undoCap) {
                            inverses.add(applied.inverseEffect());
                        } else {
                            undoTruncated = true;
                        }
                    } else if (r instanceof RollbackResult.Skipped skipped) {
                        totalSkipped++;
                        skipCounts.merge(skipped.reason().message(), 1, Integer::sum);
                    }
                }
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
        Summary summary = new Summary(totalApplied, totalSkipped, inverses);
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

    private static QueryRequest forceNoGroup(QueryRequest request, RollbackMode mode) {
        EnumSet<Flag> flags = EnumSet.copyOf(request.flags());
        flags.add(Flag.NO_GROUP);
        Sort sort = mode == RollbackMode.ROLLBACK ? Sort.NEWEST_FIRST : Sort.OLDEST_FIRST;
        return new QueryRequest(request.predicates(), sort, request.limit(), flags, false);
    }

    public static Component summaryLine(Summary summary) {
        String text = summary.skipped() > 0
                ? " " + summary.applied() + " reversals. " + summary.skipped() + " skipped"
                : " " + summary.applied() + " reversals";
        return Feedback.success(text);
    }

    public record Summary(int applied, int skipped, List<RollbackEffect> inverses) {

        public Summary {
            inverses = List.copyOf(inverses);
        }
    }
}
