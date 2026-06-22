package net.medievalrp.spyglass.plugin.command.param;

import java.time.Instant;
import java.util.List;
import net.medievalrp.spyglass.api.param.ParamParseException;
import net.medievalrp.spyglass.api.param.QueryParamHandler;
import net.medievalrp.spyglass.api.query.QueryPredicate;
import net.medievalrp.spyglass.api.util.Duration;
import org.bukkit.command.CommandSender;

/**
 * {@code before:<duration>} - upper time bound on {@code occurred}.
 *
 * <p>Emits {@code Range("occurred", null, upper)} where {@code upper}
 * is {@code now minus duration}. Use with {@code t:}/{@code since:} to
 * express a historical window: {@code t:12h before:6h} = events between
 * 12h and 6h ago.
 *
 * <p>This param intentionally does NOT set the parser's {@code sawTime}
 * flag. {@code sawTime} suppresses the default lower-bound; {@code before:}
 * is an upper bound, so leaving the flag alone means {@code before:6h}
 * alone still gets the default floor applied (default-time .. 6h ago),
 * which is the correct bounded window.
 */
public final class BeforeParam implements QueryParamHandler {

    @Override
    public List<String> aliases() {
        return List.of("before");
    }

    @Override
    public QueryPredicate parse(String alias, String value, ParamContext context) throws ParamParseException {
        Duration duration;
        try {
            duration = Duration.parse(value);
        } catch (IllegalArgumentException | ArithmeticException ex) {
            throw new ParamParseException("Invalid duration: " + value, ex);
        }
        Instant upper = duration.before(Instant.now());
        return new QueryPredicate.Range("occurred", null, upper);
    }

    @Override
    public List<String> suggestions(CommandSender sender, String input) {
        if (input.isEmpty()) {
            return List.of("10m", "1h", "6h", "1d", "3d", "1w");
        }
        return List.of();
    }
}
