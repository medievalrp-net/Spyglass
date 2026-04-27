package net.medievalrp.spyglass.api.param;

import java.util.List;
import net.medievalrp.spyglass.api.query.QueryPredicate;
import org.bukkit.command.CommandSender;

/**
 * Extension point for adding custom search parameters to {@code
 * /sg search}, {@code /sg rollback}, etc. Register via {@link
 * net.medievalrp.spyglass.api.SpyglassApi#registerQueryParamHandler}.
 *
 * <p>A handler claims one or more aliases (e.g. {@code "faction"} so
 * users can write {@code faction=red}) and turns each
 * {@code alias=value} pair the user types into a {@link
 * QueryPredicate} that the storage backend can evaluate.
 *
 * <h2>Threading</h2>
 *
 * <p>{@link #parse} and {@link #suggestions} are invoked on the
 * <b>main server thread</b> during command parsing and tab-complete.
 * Implementations must not block on I/O or external services; resolve
 * needed data eagerly during plugin startup and cache it.
 *
 * <h2>Error handling</h2>
 *
 * <p>{@link #parse} throws {@link ParamParseException} for invalid
 * user input — the message is shown directly to the player. Throwing
 * any other {@link RuntimeException} aborts the command with a
 * generic error and an entry in the server log.
 *
 * <h2>Lifetime</h2>
 *
 * <p>Handlers are held for the lifetime of the Spyglass plugin
 * instance; aliases are lowercased and indexed at registration time.
 * Re-registering the same alias replaces the prior handler.
 */
public interface QueryParamHandler {

    /**
     * Aliases this handler claims. Each alias the user types
     * ({@code alias=value}) routes to {@link #parse} with the alias
     * passed verbatim so a single handler can disambiguate multiple
     * aliases. Aliases are matched case-insensitively.
     *
     * <p>Returned list must be non-empty and stable across calls.
     */
    List<String> aliases();

    /**
     * Parse a single {@code alias=value} pair into a predicate.
     *
     * @param alias the alias the user typed (lowercased); always one
     * of {@link #aliases()}
     * @param value the literal text after the {@code =}; trim and
     * validate as needed
     * @param context the parse-time environment (sender, sender
     * location, configured max radius)
     * @return a non-null predicate; for "no constraint", return a
     * trivially-true predicate (e.g. an {@link
     * QueryPredicate.Exists} on a guaranteed field)
     * @throws ParamParseException with a user-facing message when
     * {@code value} is malformed
     */
    QueryPredicate parse(String alias, String value, ParamContext context) throws ParamParseException;

    /**
     * Whether this alias should suppress the implicit
     * {@code radius=N} that {@code /sg search} normally injects
     * when no spatial constraint is present. Override to return
     * {@code true} for aliases that already constrain the search
     * spatially or globally (e.g. a faction-territory param).
     */
    default boolean suppressesDefaultRadius(String alias) {
        return false;
    }

    /**
     * Tab-completion suggestions for the value side of {@code
     * alias=}. Called on the main thread; return an empty list (the
     * default) to skip completion. Suggestions are matched
     * case-insensitively against {@code input} by the caller.
     */
    default List<String> suggestions(CommandSender sender, String input) {
        return List.of();
    }

    /**
     * Parse-time environment passed into {@link #parse}.
     *
     * @param sender the command sender; may be a console, player, or
     * command block
     * @param senderLocation the sender's location for spatial
     * defaulting; {@code null} when the sender has no location
     * (console)
     * @param maxRadius the configured upper bound for radius-style
     * params; clamp values to this
     */
    record ParamContext(
            CommandSender sender,
            net.medievalrp.spyglass.api.util.BlockLocation senderLocation,
            int maxRadius) {
    }
}
