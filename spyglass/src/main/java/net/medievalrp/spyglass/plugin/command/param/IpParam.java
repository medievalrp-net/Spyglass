package net.medievalrp.spyglass.plugin.command.param;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import net.medievalrp.spyglass.api.param.ParamParseException;
import net.medievalrp.spyglass.api.param.QueryParamHandler;
import net.medievalrp.spyglass.api.query.QueryPredicate;
import org.bukkit.command.CommandSender;

/**
 * {@code ip:192.168.1.100} — find every event by the player(s) who joined
 * from this IP, plus the join records themselves.
 *
 * <p>Older revisions only emitted {@code Eq("address", X)}, which only
 * matches {@link net.medievalrp.spyglass.api.event.JoinRecord} (the only
 * subtype with an address column). Combining {@code ip:} with another
 * action filter ({@code a:break ip:1.2.3.4}) silently returned zero rows
 * because break records have no address — surprising, since the obvious
 * read is "what did the player at this IP do."
 *
 * <p>Now: {@code ip:X} resolves to the recent set of player UUIDs that
 * joined from {@code X} (capped at the
 * {@link net.medievalrp.spyglass.plugin.config.SpyglassConfig.Limits#searchResult()}
 * cap to bound the lookup), and emits an {@code Or} of the address match
 * (so join records still match precisely) and a player-UUID set match
 * (so break / chat / container events by those players come through too).
 *
 * <p>Cost: one extra synchronous query against the record store at parse
 * time. The resolver runs on the command thread; queries that don't use
 * {@code ip:} aren't affected.
 */
public final class IpParam implements QueryParamHandler {

    private final Function<String, List<UUID>> ipToPlayerIds;

    public IpParam(Function<String, List<UUID>> ipToPlayerIds) {
        this.ipToPlayerIds = ipToPlayerIds;
    }

    @Override
    public List<String> aliases() {
        return List.of("ip");
    }

    @Override
    public QueryPredicate parse(String alias, String value, ParamContext context) throws ParamParseException {
        return parse(alias, value, context, null);
    }

    /**
     * Blocking resolution of one IP to the recent set of player UUIDs that
     * joined from it. The service layer ({@code IpQueryResolver}) calls this
     * OFF the command thread so {@link #parse} never runs the store query on
     * the main thread (the spark finding: a synchronous {@code querySummary}
     * at parse time, on the tick).
     */
    public List<UUID> resolve(String ip) {
        return ipToPlayerIds.apply(ip);
    }

    /**
     * @param resolved IP -> player UUIDs already resolved off-thread. When it
     *     holds this address, the blocking resolver is skipped. Null / absent
     *     falls back to resolving inline, which keeps callers that did not
     *     pre-resolve (unit tests, any future caller) correct - just not
     *     off-thread.
     */
    public QueryPredicate parse(String alias, String value, ParamContext context,
                                Map<String, List<UUID>> resolved) throws ParamParseException {
        // IP→player correlation is PII (#48); spyglass.search alone
        // does not unlock it. Mirrors the renderer-side masking.
        // Fail closed on a null sender — there is no one to attribute
        // the permission to.
        if (context.sender() == null
                || !context.sender().hasPermission("spyglass.search.ip")) {
            throw new ParamParseException("Missing permission spyglass.search.ip.");
        }
        if (value == null || value.isBlank()) {
            throw new ParamParseException("ip requires an address.");
        }
        String ip = value.trim();
        QueryPredicate addressMatch = new QueryPredicate.Eq("address", ip);

        List<UUID> playerIds;
        if (resolved != null && resolved.containsKey(ip)) {
            // Pre-resolved off-thread by the service - no store query here.
            playerIds = resolved.get(ip);
        } else {
            try {
                playerIds = ipToPlayerIds.apply(ip);
            } catch (RuntimeException ex) {
                // Resolver blew up — log path is the search service, not here.
                // Fall back to the address-only match so the search still runs;
                // the operator just won't get the cross-event correlation.
                return addressMatch;
            }
        }
        if (playerIds == null || playerIds.isEmpty()) {
            // No one joined from this IP within the lookup window. The
            // address match alone may still catch an old join row from
            // beyond that window if the operator widened t:.
            return addressMatch;
        }
        return new QueryPredicate.Or(List.of(
                addressMatch,
                new QueryPredicate.In("source.playerId", playerIds)));
    }

    @Override
    public List<String> suggestions(CommandSender sender, String input) {
        return List.of();
    }
}
