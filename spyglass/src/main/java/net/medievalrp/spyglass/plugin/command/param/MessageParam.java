package net.medievalrp.spyglass.plugin.command.param;

import java.util.List;
import java.util.regex.Pattern;
import net.medievalrp.spyglass.api.param.ParamParseException;
import net.medievalrp.spyglass.api.param.QueryParamHandler;
import net.medievalrp.spyglass.api.query.QueryPredicate;
import org.bukkit.command.CommandSender;

/**
 * {@code m:excalibur} — case-insensitive substring match against the
 * message content of chat and command records. Fans the predicate
 * across both {@link net.medievalrp.spyglass.api.event.ChatRecord#message()}
 * and {@link net.medievalrp.spyglass.api.event.CommandRecord#commandLine()}
 * so a single param catches both.
 */
public final class MessageParam implements QueryParamHandler {

    @Override
    public List<String> aliases() {
        return List.of("m", "message");
    }

    @Override
    public QueryPredicate parse(String alias, String value, ParamContext context) throws ParamParseException {
        if (value == null || value.isBlank()) {
            throw new ParamParseException("m requires a value.");
        }
        Pattern pattern = Pattern.compile(Pattern.quote(value.trim()), Pattern.CASE_INSENSITIVE);
        return new QueryPredicate.Or(List.of(
                new QueryPredicate.Eq("message", pattern),
                new QueryPredicate.Eq("commandLine", pattern)));
    }

    @Override
    public List<String> suggestions(CommandSender sender, String input) {
        return List.of();
    }
}
