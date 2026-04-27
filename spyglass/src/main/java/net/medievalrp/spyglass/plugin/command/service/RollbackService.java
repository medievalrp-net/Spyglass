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
    private final Logger logger;

    public RollbackService(SpyglassApi api,
                           QueryStringParser parser,
                           SpyglassConfig config,
                           RollbackEngine engine,
                           UndoStack undoStack,
                           ServiceSupport support,
                           Logger logger) {
        this.api = api;
        this.parser = parser;
        this.config = config;
        this.engine = engine;
        this.undoStack = undoStack;
        this.support = support;
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
        api.query(request).whenComplete((result, error) -> {
            if (error != null) {
                logger.warning("Spyglass " + mode.label() + " failed: " + error);
                support.onMainThread(() -> sender.sendMessage(
                        Feedback.error(mode.label() + " failed: " + error.getMessage())));
                return;
            }
            // Pre-warm chunks before bouncing to main thread. {@link
            // World#getChunkAtAsync} fires the actual load on the
            // chunk-loader pool — by the time {@link #apply} runs on
            // the tick, the chunks are already resident, so neither
            // FAWE nor the per-block fallback has to block on chunk
            // I/O. Cheap if the chunks are already loaded (immediately
            // completed future); a real win for rollbacks that touch
            // unloaded chunks (e.g. a war zone the operator hasn't
            // visited recently).
            preWarmChunks(result).whenComplete((unused, warmError) -> {
                if (warmError != null) {
                    logger.fine("Spyglass chunk pre-warm warning: " + warmError);
                }
                support.onMainThread(() -> apply(sender, result, mode));
            });
        });
    }

    private CompletableFuture<Void> preWarmChunks(QueryResult result) {
        // Guard the whole thing — pre-warming is an optimization, not a
        // requirement. Anything that throws here (Bukkit not initialized
        // in tests, world lookup blew up, async API not available on
        // this server build) just falls through to the main-thread
        // apply with cold chunks, same as before this optimization
        // existed. Tests run with a stubbed Bukkit and would otherwise
        // never reach the summary message.
        try {
            Map<UUID, Set<Long>> byWorld = new HashMap<>();
            for (EventRecord record : result.records()) {
                if (!(record instanceof Rollbackable)) {
                    continue;
                }
                BlockLocation loc = record.location();
                if (loc == null) {
                    continue;
                }
                int chunkX = loc.x() >> 4;
                int chunkZ = loc.z() >> 4;
                long packed = ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
                byWorld.computeIfAbsent(loc.worldId(), k -> new HashSet<>()).add(packed);
            }
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

    private void apply(CommandSender sender, QueryResult result, RollbackMode mode) {
        List<RollbackEffect> effects = collectEffects(result, mode);
        if (effects.isEmpty()) {
            sender.sendMessage(Feedback.error("No results."));
            return;
        }
        List<RollbackResult> results = engine.applyAll(effects, sender);
        Summary summary = summarize(results);
        // Skip reasons used to emit one chat line per skipped effect.
        // For a typical "rollback 1 hour of fire spread" that's
        // hundreds of "Skip Reason: block changed" lines and you can't
        // see anything else. Aggregate by reason instead — one gray
        // line per distinct reason with the occurrence count.
        java.util.LinkedHashMap<String, Integer> skipCounts = new java.util.LinkedHashMap<>();
        for (RollbackResult result_ : results) {
            if (result_ instanceof RollbackResult.Skipped skipped) {
                skipCounts.merge(skipped.reason().message(), 1, Integer::sum);
            }
        }
        for (var entry : skipCounts.entrySet()) {
            String suffix = entry.getValue() == 1 ? "" : " ×" + entry.getValue();
            sender.sendMessage(Feedback.bonus("Skip Reason: " + entry.getKey() + suffix));
        }
        sender.sendMessage(summaryLine(summary));
        if (sender instanceof Player player && !summary.inverses.isEmpty()) {
            // Push to undo stack OFF the main thread. The push encodes
            // every inverse effect to BSON (CPU) and ships them to the
            // DB over HTTP (blocking I/O); a 150-block rollback against
            // ClickHouse held the tick for 30+ seconds before this and
            // tripped Paper's watchdog. The summary line + skip reasons
            // already went to the player; if the async push fails we
            // log it and lose just this op's undo capability, not the
            // rollback itself.
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

    private static List<RollbackEffect> collectEffects(QueryResult result, RollbackMode mode) {
        List<RollbackEffect> effects = new ArrayList<>();
        for (EventRecord record : result.records()) {
            if (record instanceof Rollbackable rollbackable) {
                effects.add(mode == RollbackMode.ROLLBACK
                        ? rollbackable.rollbackEffect()
                        : rollbackable.restoreEffect());
            }
        }
        return effects;
    }

    private static QueryRequest forceNoGroup(QueryRequest request, RollbackMode mode) {
        EnumSet<Flag> flags = EnumSet.copyOf(request.flags());
        flags.add(Flag.NO_GROUP);
        Sort sort = mode == RollbackMode.ROLLBACK ? Sort.NEWEST_FIRST : Sort.OLDEST_FIRST;
        return new QueryRequest(request.predicates(), sort, request.limit(), flags, false);
    }

    private static Summary summarize(List<RollbackResult> results) {
        int applied = 0;
        int skipped = 0;
        List<RollbackEffect> inverses = new ArrayList<>();
        for (RollbackResult result : results) {
            if (result instanceof RollbackResult.Applied appliedResult) {
                applied++;
                inverses.add(appliedResult.inverseEffect());
            } else {
                skipped++;
            }
        }
        return new Summary(applied, skipped, inverses);
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
