package net.medievalrp.spyglass.plugin.command.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Logger;
import net.medievalrp.spyglass.plugin.command.param.IpParam;
import net.medievalrp.spyglass.plugin.command.param.QueryStringParser;
import org.jetbrains.annotations.ApiStatus;

/**
 * Pre-resolves {@code ip:} query values OFF the main thread so the blocking
 * store lookup never sits on the command thread.
 *
 * <p>{@code IpParam.parse} would otherwise call the record store synchronously
 * at parse time, and commands run inline on the main server thread (the simple
 * execution coordinator). This splits the work: scan for {@code ip:} values on
 * the calling thread (cheap, pure string), resolve them on the async pool, then
 * run the parse continuation back on the main thread with the resolved UUIDs in
 * hand. Parse itself stays on the main thread, where it must be (it reads the
 * player location and the WorldEdit selection).
 */
@ApiStatus.Internal
public final class IpQueryResolver {

    private final QueryStringParser parser;
    private final IpParam ipParam;
    private final ServiceSupport support;
    private final Logger logger;

    public IpQueryResolver(QueryStringParser parser, IpParam ipParam,
                           ServiceSupport support, Logger logger) {
        this.parser = parser;
        this.ipParam = ipParam;
        this.support = support;
        this.logger = logger;
    }

    /**
     * Resolve any {@code ip:} addresses in {@code raw}, then hand the resolved
     * map to {@code continuation} ON THE MAIN THREAD.
     *
     * <p>Fast path: when the query has no {@code ip:} token there is nothing to
     * resolve, so {@code continuation} runs immediately on the calling thread
     * with an empty map - identical timing to the old direct-parse path for the
     * overwhelming majority of searches.
     */
    public void resolve(String raw, Consumer<Map<String, List<UUID>>> continuation) {
        List<String> ips = parser.extractIpValues(raw);
        if (ips == null || ips.isEmpty()) {
            continuation.accept(Map.of());
            return;
        }
        support.onAsyncThread(() -> {
            Map<String, List<UUID>> resolved = new HashMap<>();
            for (String ip : ips) {
                try {
                    resolved.put(ip, ipParam.resolve(ip));
                } catch (RuntimeException ex) {
                    // Leave it unresolved; IpParam falls back to an address-only
                    // match for a missing entry. Don't fail the whole search.
                    logger.warning("Spyglass ip: resolve failed for " + ip + ": " + ex.getMessage());
                }
            }
            support.onMainThread(() -> continuation.accept(resolved));
        });
    }
}
