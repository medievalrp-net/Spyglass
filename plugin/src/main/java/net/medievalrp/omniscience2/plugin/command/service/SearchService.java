package net.medievalrp.omniscience2.plugin.command.service;

import java.util.List;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.medievalrp.omniscience2.api.Omniscience2Api;
import net.medievalrp.omniscience2.api.param.ParamParseException;
import net.medievalrp.omniscience2.api.query.Flag;
import net.medievalrp.omniscience2.api.query.QueryRequest;
import net.medievalrp.omniscience2.api.query.QueryResult;
import net.medievalrp.omniscience2.plugin.command.PageCache;
import net.medievalrp.omniscience2.plugin.command.param.QueryStringParser;
import net.medievalrp.omniscience2.plugin.command.render.ResultRenderer;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class SearchService {

    private final Omniscience2Api api;
    private final QueryStringParser parser;
    private final ResultRenderer renderer;
    private final PageCache pageCache;
    private final ServiceSupport support;
    private final Logger logger;

    public SearchService(Omniscience2Api api,
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
            sender.sendMessage(ServiceSupport.errorMessage(ex.getMessage()));
            return;
        }
        executeRequest(sender, request);
    }

    public void executeRequest(CommandSender sender, QueryRequest request) {
        sender.sendMessage(ServiceSupport.infoMessage("Searching..."));
        api.query(request).whenComplete((result, error) -> {
            if (error != null) {
                logger.warning("Omniscience2 search failed: " + error);
                support.onMainThread(() -> sender.sendMessage(
                        ServiceSupport.errorMessage("Query failed: " + error.getMessage())));
                return;
            }
            support.onMainThread(() -> handleResults(sender, request, result));
        });
    }

    private void handleResults(CommandSender sender, QueryRequest request, QueryResult result) {
        List<Component> lines = renderLines(request, result);
        if (lines.isEmpty()) {
            pageCache.clear(sender);
            sender.sendMessage(ServiceSupport.warnMessage("No matching records."));
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
        return result.records().stream().map(renderer::renderSingle).toList();
    }
}
