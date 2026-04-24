package net.medievalrp.omniscience2.plugin.command.service;

import net.medievalrp.omniscience2.plugin.command.render.Feedback;

import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.function.IntFunction;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.medievalrp.omniscience2.api.Omniscience2Api;
import net.medievalrp.omniscience2.api.event.EventRecord;
import net.medievalrp.omniscience2.api.param.ParamParseException;
import net.medievalrp.omniscience2.api.query.Flag;
import net.medievalrp.omniscience2.api.query.QueryRequest;
import net.medievalrp.omniscience2.api.query.QueryResult;
import net.medievalrp.omniscience2.plugin.api.Omniscience2ApiImpl;
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
            sender.sendMessage(Feedback.error(ex.getMessage()));
            return;
        }
        executeRequest(sender, request);
    }

    public void executeRequest(CommandSender sender, QueryRequest request) {
        sender.sendMessage(Feedback.querying());
        searchQuery(request).whenComplete((result, error) -> {
            if (error != null) {
                logger.warning("Omniscience2 search failed: " + error);
                support.onMainThread(() -> sender.sendMessage(
                        Feedback.error("Query failed: " + error.getMessage())));
                return;
            }
            support.onMainThread(() -> handleResults(sender, request, result));
        });
    }

    /**
     * Use the summary-projection fast path when the concrete plugin impl
     * is wired in; fall back to the full {@link Omniscience2Api#query}
     * path when something has swapped the API (tests, future alternative
     * implementations). The renderer never reads the skipped fields, so
     * the summary path is always safe for the display-only flow.
     */
    private CompletionStage<QueryResult> searchQuery(QueryRequest request) {
        if (api instanceof Omniscience2ApiImpl impl) {
            return impl.querySummary(request);
        }
        return api.query(request);
    }

    private void handleResults(CommandSender sender, QueryRequest request, QueryResult result) {
        boolean grouping = request.grouping()
                && !request.flags().contains(Flag.NO_GROUP)
                && !result.aggregations().isEmpty();
        int count = grouping ? result.aggregations().size() : result.records().size();
        if (count == 0) {
            pageCache.clear(sender);
            sender.sendMessage(Feedback.error("No results."));
            return;
        }
        // Cache a lazy line source: the renderer runs only on page flip, so
        // show(sender, 1) renders at most PAGE_SIZE Components on the main
        // thread instead of the full result-set (1 000 × ~25 nodes each).
        // Keeping the underlying records/aggregations list closed-over is
        // cheap — the typed record graph is already allocated by the
        // Mongo decode.
        IntFunction<Component> lines;
        if (grouping) {
            List<QueryResult.RecordAggregation> aggregations = result.aggregations();
            lines = index -> renderer.renderAggregation(aggregations.get(index));
        } else {
            List<EventRecord> records = result.records();
            var flags = request.flags();
            lines = index -> renderer.renderSingle(records.get(index), flags);
        }
        pageCache.store(sender, count, lines);
        pageCache.show(sender, 1);
    }
}
