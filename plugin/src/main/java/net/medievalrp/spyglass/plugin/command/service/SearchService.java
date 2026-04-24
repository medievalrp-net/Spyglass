package net.medievalrp.spyglass.plugin.command.service;

import net.medievalrp.spyglass.plugin.command.render.Feedback;

import java.util.List;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.medievalrp.spyglass.api.SpyglassApi;
import net.medievalrp.spyglass.api.param.ParamParseException;
import net.medievalrp.spyglass.api.query.Flag;
import net.medievalrp.spyglass.api.query.QueryRequest;
import net.medievalrp.spyglass.api.query.QueryResult;
import net.medievalrp.spyglass.plugin.command.PageCache;
import net.medievalrp.spyglass.plugin.command.param.QueryStringParser;
import net.medievalrp.spyglass.plugin.command.render.ResultRenderer;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class SearchService {

    private final SpyglassApi api;
    private final QueryStringParser parser;
    private final ResultRenderer renderer;
    private final PageCache pageCache;
    private final ServiceSupport support;
    private final Logger logger;

    public SearchService(SpyglassApi api,
                         QueryStringParser parser,
                         ResultRenderer renderer,
                         PageCache pageCache,
                         ServiceSupport support,
                         Logger logger) {
        this.api = api;
        this.parser = parser;
        this.renderer = renderer;
        this.pageCache = pageCache;
        this.support = support;
        this.logger = logger;
    }

    public void execute(CommandSender sender, String raw) {
        QueryRequest request;
        try {
            request = parser.parse(sender, raw, 0);
        } catch (ParamParseException ex) {
            sender.sendMessage(Feedback.error(ex.getMessage()));
            return;
        }
        executeRequest(sender, request);
    }

    public void executeRequest(CommandSender sender, QueryRequest request) {
        sender.sendMessage(Feedback.querying());
        api.query(request).whenComplete((result, error) -> {
            if (error != null) {
                logger.warning("Spyglass search failed: " + error);
                support.onMainThread(() -> sender.sendMessage(
                        Feedback.error("Query failed: " + error.getMessage())));
                return;
            }
            support.onMainThread(() -> handleResults(sender, request, result));
        });
    }

    private void handleResults(CommandSender sender, QueryRequest request, QueryResult result) {
        List<Component> lines = renderLines(request, result);
        if (lines.isEmpty()) {
            pageCache.clear(sender);
            sender.sendMessage(Feedback.error("No results."));
            return;
        }
        pageCache.store(sender, lines);
        pageCache.show(sender, 1);
    }

    private List<Component> renderLines(QueryRequest request, QueryResult result) {
        boolean grouping = request.grouping()
                && !request.flags().contains(Flag.NO_GROUP)
                && !result.aggregations().isEmpty();
        if (grouping) {
            return result.aggregations().stream().map(renderer::renderAggregation).toList();
        }
        return result.records().stream()
                .map(record -> renderer.renderSingle(record, request.flags()))
                .toList();
    }
}
