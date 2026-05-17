package net.medievalrp.spyglass.api;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.logging.Logger;
import net.medievalrp.spyglass.api.event.EventRecord;
import net.medievalrp.spyglass.api.event.RecordCommittedEvent;
import net.medievalrp.spyglass.api.extension.DisplayRenderer;
import net.medievalrp.spyglass.api.extension.FlagHandler;
import net.medievalrp.spyglass.api.param.QueryParamHandler;
import net.medievalrp.spyglass.api.query.QueryRequest;
import net.medievalrp.spyglass.api.query.QueryResult;
import net.medievalrp.spyglass.api.rollback.RollbackEffectHandler;

/**
 * Public entry point for plugins that want to record forensic events,
 * query the event log, or extend search/display behaviour.
 *
 * <p>Obtain the singleton via Bukkit's services manager:
 * <pre>{@code
 * SpyglassApi api = Bukkit.getServicesManager()
 *         .load(SpyglassApi.class);
 * }</pre>
 *
 * <h2>Threading</h2>
 *
 * <p>All methods on this interface are safe to call from the main
 * server thread. {@link #record(EventRecord)} hands off to an async
 * recorder (non-blocking, lock-free intake). {@link
 * #query(QueryRequest)} dispatches to a worker pool and returns a
 * {@link CompletionStage}; chain with {@code thenAcceptAsync} (or
 * {@code thenAccept} if your continuation must run on the calling
 * thread). Registration methods ({@link
 * #registerQueryParamHandler}, {@link #registerFlagHandler},
 * {@link #registerDisplayRenderer},
 * {@link #registerRollbackEffectHandler}) are intended to run during
 * your plugin's {@code onEnable()} on the main thread; they are not
 * safe to call concurrently from multiple threads.
 *
 * <h2>Versioning</h2>
 *
 * <p>This interface is the stable extension surface. Methods will be
 * added over time but never removed within a major version. Default
 * methods on extension interfaces ({@link DisplayRenderer}, {@link
 * QueryParamHandler}, {@link FlagHandler}) shield existing
 * implementors from new capabilities.
 */
public interface SpyglassApi {

    /**
     * Record a forensic event. Returns immediately; the record is
     * fanned out to the async recorder and persisted on the next
     * drain cycle (typically &lt;= 250 ms).
     *
     * <p>Safe to call from any thread, including the main server
     * thread. Never blocks. Fires
     * {@link RecordCommittedEvent} synchronously after intake.
     */
    void record(EventRecord record);

    /**
     * Run a query against the event log. The returned stage completes
     * on a worker thread; chain with {@code thenAcceptAsync} (or use
     * Bukkit's main-thread executor) before touching world state.
     *
     * <p>Errors during the query (storage outage, malformed
     * predicate, etc.) complete the stage exceptionally rather than
     * throwing synchronously.
     */
    CompletionStage<QueryResult> query(QueryRequest request);

    /**
     * Register a custom query-parameter handler so {@code /spyglass
     * search yourparam=foo} parses your alias.
     *
     * <p>Call from {@code onEnable()} on the main thread. Aliases are
     * lowercased on registration; subsequent calls with overlapping
     * aliases overwrite previous handlers.
     */
    void registerQueryParamHandler(QueryParamHandler handler);

    /**
     * Look up a registered query-param handler by alias. Lookup is
     * case-insensitive.
     */
    Optional<QueryParamHandler> queryParam(String alias);

    /**
     * Snapshot of all registered query-param handlers, in
     * registration order, deduplicated when one handler claims
     * multiple aliases.
     */
    List<QueryParamHandler> queryParams();

    /**
     * Register a custom flag handler so {@code /spyglass search
     * -yourflag} or {@code -yourflag=value} parses your alias.
     *
     * <p>Built-in flag aliases ({@code ng}, {@code g}, {@code nc},
     * {@code ex}, {@code we}, {@code ord}, {@code nod}) cannot be
     * shadowed; the parser checks built-ins first.
     *
     * <p>Call from {@code onEnable()} on the main thread.
     */
    void registerFlagHandler(FlagHandler handler);

    /**
     * Look up a registered flag handler by alias. Lookup is
     * case-insensitive.
     */
    Optional<FlagHandler> flag(String alias);

    /**
     * Snapshot of all registered flag handlers, in registration
     * order, deduplicated when one handler claims multiple aliases.
     */
    List<FlagHandler> flags();

    /**
     * Register a display renderer that customises how a specific
     * event type is shown in search results and the inspection wand.
     *
     * <p>Call from {@code onEnable()} on the main thread. Event names
     * are lowercased on registration; the most-recent registration
     * for a given name wins.
     */
    void registerDisplayRenderer(String eventName, DisplayRenderer renderer);

    /**
     * Look up a registered display renderer by event name. Lookup is
     * case-insensitive.
     */
    Optional<DisplayRenderer> displayRenderer(String eventName);

    /**
     * Register a rollback effect handler so
     * {@link net.medievalrp.spyglass.api.rollback.RollbackEffect.Custom}
     * payloads with a matching {@code type} are routed to your code
     * during {@code /spyglass rollback} and {@code /spyglass undo}.
     *
     * <p>Call from {@code onEnable()} on the main thread. Types are
     * lowercased on registration.
     */
    void registerRollbackEffectHandler(RollbackEffectHandler handler);

    /**
     * Look up a registered rollback effect handler by type. Lookup
     * is case-insensitive.
     */
    Optional<RollbackEffectHandler> rollbackEffectHandler(String type);

    /**
     * The set of event names this Spyglass install is configured
     * to record. Useful for extensions that want to hide UI for
     * disabled events; the set is immutable.
     */
    Set<String> enabledEvents();

    /**
     * Read-only view of the operator's query / storage limits — for
     * extensions that need to align their own bounds with the
     * configured maxRadius, retention, etc.
     */
    SpyglassLimits limits();

    /**
     * Identifier the recording server stamps onto every {@link
     * net.medievalrp.spyglass.api.event.EventRecord} it emits, drawn
     * from {@code server.name} in Spyglass's config. External recorders
     * (other plugins pushing events through {@link
     * #record(net.medievalrp.spyglass.api.event.EventRecord)}) must
     * pass this same value into {@link
     * net.medievalrp.spyglass.api.event.RecordContext#fresh} so their
     * records line up with the host server's in cross-server queries.
     */
    String serverName();

    /**
     * The Spyglass plugin's logger. Use this for diagnostics that
     * should appear under the Spyglass log scope rather than your
     * own (e.g. when a custom flag handler rejects malformed config
     * during startup). Most plugins should prefer their own logger.
     */
    Logger logger();
}
