package net.medievalrp.spyglass.plugin.command.param;

import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import net.medievalrp.spyglass.api.param.ParamParseException;
import net.medievalrp.spyglass.api.param.QueryParamHandler;
import net.medievalrp.spyglass.api.query.QueryPredicate;
import org.bukkit.command.CommandSender;

/**
 * {@code content:...} — case-insensitive content search over the message of
 * chat / command / custom records, with optional regex. The richer sibling of
 * {@code m:} (which is always a plain literal substring): use {@code content:}
 * when you need a pattern.
 *
 * <ul>
 *   <li>{@code content:slur} or {@code content:"how are we today"} — literal,
 *       case-insensitive substring.</li>
 *   <li>{@code content:/(slur1|slur2)/} — the {@code /.../}-delimited form is
 *       compiled as a case-insensitive regular expression.</li>
 * </ul>
 *
 * <p>Fans across {@link net.medievalrp.spyglass.api.event.ChatRecord#message()},
 * {@link net.medievalrp.spyglass.api.event.CustomRecord#message()}, and
 * {@link net.medievalrp.spyglass.api.event.CommandRecord#commandLine()} — both
 * live in the {@code message} / {@code commandLine} columns. On the ClickHouse
 * and Mongo backends the pattern pushes down to a server-side regex; on SQLite
 * (message in the blob) it runs as an in-memory post-filter.
 */
public final class ContentParam implements QueryParamHandler {

    @Override
    public List<String> aliases() {
        return List.of("content");
    }

    @Override
    public QueryPredicate parse(String alias, String value, ParamContext context) throws ParamParseException {
        if (value == null || value.isBlank()) {
            throw new ParamParseException("content requires a value.");
        }
        String safe = ItemFieldParams.requireSafeTerm(alias, value.trim());
        Pattern pattern = isRegexForm(safe) ? compileRegex(safe) : literal(safe);
        return new QueryPredicate.Or(List.of(
                new QueryPredicate.Eq("message", pattern),
                new QueryPredicate.Eq("commandLine", pattern)));
    }

    /** {@code /.../} with something between the slashes is the regex form. */
    private static boolean isRegexForm(String value) {
        return value.length() >= 3 && value.startsWith("/") && value.endsWith("/");
    }

    private static Pattern compileRegex(String value) throws ParamParseException {
        String body = value.substring(1, value.length() - 1);
        try {
            return Pattern.compile(body, Pattern.CASE_INSENSITIVE);
        } catch (PatternSyntaxException ex) {
            throw new ParamParseException("Invalid content regex: " + ex.getMessage());
        }
    }

    /** Plain case-insensitive substring — Pattern.quote defangs any metachars. */
    private static Pattern literal(String value) {
        return Pattern.compile(Pattern.quote(value), Pattern.CASE_INSENSITIVE);
    }

    @Override
    public List<String> suggestions(CommandSender sender, String input) {
        return List.of();
    }
}
