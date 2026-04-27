package net.medievalrp.spyglass.api.extension;

import java.util.List;
import net.medievalrp.spyglass.api.param.ParamParseException;
import net.medievalrp.spyglass.api.param.QueryParamHandler.ParamContext;
import net.medievalrp.spyglass.api.query.QueryPredicate;
import org.bukkit.command.CommandSender;

/**
 * Extension point for custom dash-prefixed query flags. Where
 * {@link net.medievalrp.spyglass.api.param.QueryParamHandler}
 * adds {@code alias=value} parameters, this interface adds
 * {@code -alias} or {@code -alias=value} flags.
 *
 * <p>The four built-in flags ({@code -ng}, {@code -g}, {@code -nc},
 * {@code -ex}) toggle internal grouping, radius, chat-exclusion, and
 * extended-render behaviour and aren't extensible. Custom flags
 * registered through this interface are syntactic shortcuts for a
 * predicate the storage backend can evaluate — same model as a
 * param, different surface.
 *
 * <h2>Registration</h2>
 *
 * <p>Register from your plugin's {@code onEnable()}:
 * <pre>{@code
 * api.registerFlagHandler(new MyFlagHandler());
 * }</pre>
 *
 * <h2>Threading</h2>
 *
 * <p>{@link #parse} and {@link #suggestions} are invoked on the
 * <b>main server thread</b> during command parsing and tab-complete.
 * Don't block on I/O; resolve any data eagerly during startup.
 *
 * <h2>Error handling</h2>
 *
 * <p>{@link #parse} throws {@link ParamParseException} for invalid
 * input (the message is shown to the player). Other
 * {@link RuntimeException}s abort the command with a generic error.
 *
 * <h2>Conflicts</h2>
 *
 * <p>Custom flag aliases must not collide with the built-in flags
 * ({@code ng}, {@code nogroup}, {@code g}, {@code global},
 * {@code nc}, {@code nochat}, {@code ex}, {@code extended},
 * {@code we}, {@code worldedit}, {@code ord}, {@code order},
 * {@code nod}, {@code nodefault}). Built-ins always win.
 */
public interface FlagHandler {

    /**
     * Aliases this handler claims. Each alias the user types
     * ({@code -alias} or {@code -alias=value}) routes to
     * {@link #parse}. Aliases are matched case-insensitively.
     */
    List<String> aliases();

    /**
     * Parse a {@code -alias} (no-value) or {@code -alias=value} into
     * a predicate.
     *
     * @param alias the alias the user typed (lowercased); always one
     * of {@link #aliases()}
     * @param value the literal text after {@code =}, or {@code null}
     * when the user wrote a bare {@code -alias}
     * @param context parse-time environment (sender, sender
     * location, configured max radius)
     * @return a non-null predicate
     * @throws ParamParseException when {@code value} is malformed or
     * missing when required
     */
    QueryPredicate parse(String alias, String value, ParamContext context) throws ParamParseException;

    /**
     * Whether this flag should suppress the implicit
     * {@code radius=N} that {@code /sg search} injects when no
     * spatial constraint is present. Override to {@code true} for
     * flags that already constrain spatially or globally.
     */
    default boolean suppressesDefaultRadius(String alias) {
        return false;
    }

    /**
     * Tab-completion suggestions for the value side of
     * {@code -alias=}. Called on the main thread; return an empty
     * list (the default) for value-less flags.
     */
    default List<String> suggestions(CommandSender sender, String alias, String input) {
        return List.of();
    }
}
