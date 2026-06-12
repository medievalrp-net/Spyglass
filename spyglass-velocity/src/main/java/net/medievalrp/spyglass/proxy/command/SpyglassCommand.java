package net.medievalrp.spyglass.proxy.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.medievalrp.spyglass.api.event.EventRecord;
import net.medievalrp.spyglass.api.query.Flag;
import net.medievalrp.spyglass.api.query.QueryPredicate;
import net.medievalrp.spyglass.api.query.QueryRequest;
import net.medievalrp.spyglass.api.query.QueryResult;
import net.medievalrp.spyglass.api.query.Sort;
import net.medievalrp.spyglass.plugin.storage.RecordStore;
import net.medievalrp.spyglass.proxy.config.SpyglassProxyConfig;
import org.slf4j.Logger;

/**
 * /spyglass entrypoint on the proxy. Dispatches to:
 *
 * <ul>
 *   <li>{@code search [predicates...]} - run a query, render the first
 *       page of results, store the rest for paging</li>
 *   <li>{@code page [n]} - move within the last result set</li>
 *   <li>{@code help} - show param syntax</li>
 * </ul>
 *
 * <p>Per-source state for pagination is keyed on player UUID (or a
 * fixed sentinel for console) and capped at one result set per source.
 * Memory cost is bounded by {@code limits.search-result} × concurrent
 * operators.
 */
public final class SpyglassCommand implements SimpleCommand {

    private static final UUID CONSOLE_KEY = new UUID(0L, 0L);

    private final RecordStore store;
    private final SpyglassProxyConfig config;
    private final Logger logger;
    private final ProxyQueryStringParser parser;
    private final ProxyResultRenderer renderer = new ProxyResultRenderer();
    private final ConcurrentMap<UUID, ResultBuffer> buffers = new ConcurrentHashMap<>();

    public SpyglassCommand(RecordStore store, SpyglassProxyConfig config, Logger logger) {
        this.store = store;
        this.config = config;
        this.logger = logger;
        // ip:<addr> resolver — synchronous lookup against the same
        // record store. Capped at the search-result limit so we never
        // pull more than the operator could see anyway.
        int limit = config.limits().searchResult();
        long defaultTimeSeconds = config.defaults().time().seconds();
        this.parser = new ProxyQueryStringParser(limit, defaultTimeSeconds, ip -> {
            QueryRequest joinReq = new QueryRequest(
                    List.of(new QueryPredicate.Eq("event", "join"),
                            new QueryPredicate.Eq("address", ip)),
                    Sort.NEWEST_FIRST,
                    limit,
                    EnumSet.noneOf(Flag.class),
                    false);
            return store.querySummary(joinReq).records().stream()
                    .map(r -> r.source() == null ? null : r.source().playerId())
                    .filter(java.util.Objects::nonNull)
                    .distinct()
                    .toList();
        });
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        if (!source.hasPermission("spyglass.search")) {
            source.sendMessage(Component.text("You lack permission to use /spyglass.",
                    NamedTextColor.RED));
            return;
        }

        String[] args = invocation.arguments();
        if (args.length == 0 || "help".equalsIgnoreCase(args[0])) {
            sendHelp(source);
            return;
        }

        String sub = args[0].toLowerCase(java.util.Locale.ROOT);
        String[] rest = Arrays.copyOfRange(args, 1, args.length);

        switch (sub) {
            case "search" -> runSearch(source, String.join(" ", rest));
            case "page" -> runPage(source, rest);
            default -> {
                // Treat anything that doesn't match a subcommand as a
                // search query: matches the Paper-side ergonomic of
                // skipping `search` for fast typing.
                runSearch(source, String.join(" ", args));
            }
        }
    }

    private void runSearch(CommandSource source, String raw) {
        // /sgv search with no params would otherwise pull every record
        // in the default 3-day window, which on a busy proxy is "show me
        // everything" — never what an operator means. Make them say
        // something concrete (srv:, p:, a:, t:, ...).
        if (raw == null || raw.isBlank()) {
            source.sendMessage(Component.text(
                    "search needs at least one filter. Try /sgv help.",
                    NamedTextColor.YELLOW));
            sendHelp(source);
            return;
        }
        // One permission read per invocation: gates the ip: param at
        // parse time and decides whether join IPs render or mask (#48).
        boolean showIp = source.hasPermission("spyglass.search.ip");
        QueryRequest request;
        try {
            request = parser.parse(raw, showIp);
        } catch (ProxyQueryStringParser.ParseException ex) {
            source.sendMessage(Component.text(ex.getMessage(), NamedTextColor.RED));
            return;
        }

        source.sendMessage(Component.text("Querying records...", NamedTextColor.DARK_AQUA));
        UUID key = sourceKey(source);
        CompletableFuture.supplyAsync(() -> store.query(request))
                .whenComplete((result, error) -> {
                    if (error != null) {
                        logger.error("Spyglass query failed", error);
                        source.sendMessage(Component.text(
                                "Query failed: " + error.getMessage(), NamedTextColor.RED));
                        return;
                    }
                    deliver(source, key, result, request.flags(), showIp);
                });
    }

    private void deliver(CommandSource source, UUID key, QueryResult result, EnumSet<Flag> flags,
                         boolean showIp) {
        boolean grouping = !flags.contains(Flag.NO_GROUP);
        List<Component> rendered = renderRows(result, flags, grouping, showIp);
        if (rendered.isEmpty()) {
            source.sendMessage(Component.text("No matching records.", NamedTextColor.GRAY));
            buffers.remove(key);
            return;
        }
        ResultBuffer buffer = new ResultBuffer(rendered, config.limits().pageSize());
        buffers.put(key, buffer);
        sendPage(source, buffer, 1);
    }

    private List<Component> renderRows(QueryResult result, EnumSet<Flag> flags, boolean grouping,
                                       boolean showIp) {
        List<Component> rows = new ArrayList<>();
        if (grouping && !flags.contains(Flag.NO_GROUP) && !result.aggregations().isEmpty()) {
            for (QueryResult.RecordAggregation agg : result.aggregations()) {
                rows.add(renderer.renderAggregation(agg, showIp));
            }
        } else {
            for (EventRecord record : result.records()) {
                rows.add(renderer.renderSingle(record, flags, showIp));
            }
        }
        return rows;
    }

    private void runPage(CommandSource source, String[] args) {
        UUID key = sourceKey(source);
        ResultBuffer buffer = buffers.get(key);
        if (buffer == null) {
            source.sendMessage(Component.text("No previous search to page through.",
                    NamedTextColor.RED));
            return;
        }
        int target;
        if (args.length == 0) {
            target = buffer.page() + 1;
        } else {
            try {
                target = Integer.parseInt(args[0]);
            } catch (NumberFormatException ex) {
                source.sendMessage(Component.text("page expects a number.", NamedTextColor.RED));
                return;
            }
        }
        sendPage(source, buffer, target);
    }

    private void sendPage(CommandSource source, ResultBuffer buffer, int requestedPage) {
        int totalPages = buffer.totalPages();
        int page = Math.max(1, Math.min(totalPages, requestedPage));
        buffer.setPage(page);
        source.sendMessage(ProxyResultRenderer.pageHeader(page, totalPages, buffer.totalResults()));
        for (Component row : buffer.pageRows()) {
            source.sendMessage(row);
        }
    }

    private static UUID sourceKey(CommandSource source) {
        if (source instanceof Player player) {
            return player.getUniqueId();
        }
        return CONSOLE_KEY;
    }

    private void sendHelp(CommandSource source) {
        source.sendMessage(Component.text("/sgv search <params...>", NamedTextColor.AQUA));
        source.sendMessage(Component.text("  srv:<name>     filter to one backend server",
                NamedTextColor.GRAY));
        source.sendMessage(Component.text("  p:<player>     filter by player name",
                NamedTextColor.GRAY));
        source.sendMessage(Component.text("  a:<event>      filter by event (break, place, say, ...)",
                NamedTextColor.GRAY));
        source.sendMessage(Component.text("  t:<n><unit>    time window (e.g. t:1d, t:6h)",
                NamedTextColor.GRAY));
        source.sendMessage(Component.text("  m:<text>       chat / command line substring",
                NamedTextColor.GRAY));
        source.sendMessage(Component.text("  target:<x>     target string (block / item / entity name)",
                NamedTextColor.GRAY));
        source.sendMessage(Component.text("  ip:<addr>      join IP",
                NamedTextColor.GRAY));
        source.sendMessage(Component.text("  -ng -nc -ex    nogroup / nochat / extended",
                NamedTextColor.GRAY));
        source.sendMessage(Component.text("  -ord:asc|desc  sort order",
                NamedTextColor.GRAY));
        source.sendMessage(Component.text("/sgv page [n]      jump to page n (next page if blank)",
                NamedTextColor.AQUA));
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission("spyglass.search");
    }

    /**
     * Per-source pagination state. Holds the rendered rows so reflowing
     * pages doesn't re-query the backend.
     */
    private static final class ResultBuffer {
        private final List<Component> rows;
        private final int pageSize;
        private int page = 1;

        ResultBuffer(List<Component> rows, int pageSize) {
            this.rows = rows;
            this.pageSize = Math.max(1, pageSize);
        }

        int page() {
            return page;
        }

        void setPage(int page) {
            this.page = page;
        }

        int totalResults() {
            return rows.size();
        }

        int totalPages() {
            return Math.max(1, (rows.size() + pageSize - 1) / pageSize);
        }

        List<Component> pageRows() {
            int from = (page - 1) * pageSize;
            int to = Math.min(rows.size(), from + pageSize);
            if (from >= to) {
                return List.of();
            }
            return rows.subList(from, to);
        }
    }
}
