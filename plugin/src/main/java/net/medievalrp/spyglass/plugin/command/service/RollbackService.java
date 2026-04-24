package net.medievalrp.spyglass.plugin.command.service;

import net.medievalrp.spyglass.plugin.command.render.Feedback;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.logging.Logger;
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
            support.onMainThread(() -> apply(sender, result, mode));
        });
    }

    private void apply(CommandSender sender, QueryResult result, RollbackMode mode) {
        List<RollbackEffect> effects = collectEffects(result, mode);
        if (effects.isEmpty()) {
            sender.sendMessage(Feedback.error("No results."));
            return;
        }
        List<RollbackResult> results = engine.applyAll(effects, sender);
        Summary summary = summarize(results);
        // Skip reasons go out first (gray, one per skipped effect), then the
        // green " N reversals" / " N reversals. M skipped" summary - matching
        // v1's chat output exactly.
        for (RollbackResult result_ : results) {
            if (result_ instanceof RollbackResult.Skipped skipped) {
                sender.sendMessage(Feedback.bonus("Skip Reason: " + skipped.reason().message()));
            }
        }
        sender.sendMessage(summaryLine(summary));
        if (sender instanceof Player player && !summary.inverses.isEmpty()) {
            undoStack.push(player.getUniqueId(), mode.name(), summary.inverses);
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
