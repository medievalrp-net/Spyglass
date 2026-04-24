package net.medievalrp.spyglass.plugin.command.param;

import java.util.List;
import java.util.regex.Pattern;
import net.medievalrp.spyglass.api.param.ParamParseException;
import net.medievalrp.spyglass.api.param.QueryParamHandler;
import net.medievalrp.spyglass.api.query.QueryPredicate;
import org.bukkit.command.CommandSender;

/**
 * {@code trg:chest} — case-insensitive substring regex against the
 * record's {@code target} field. General-purpose fallback for when the
 * typed params ({@code b:}, {@code i:}, {@code e:}) don't quite fit
 * what the operator remembers about the record.
 *
 * <p>Regex is anchored as a {@code Pattern.quote}-wrapped substring,
 * so user input is always treated as literal text rather than a regex
 * fragment. Search is case-insensitive.
 */
public final class TargetParam implements QueryParamHandler {

    @Override
    public List<String> aliases() {
        return List.of("trg", "target");
    }

    @Override
    public QueryPredicate parse(String alias, String value, ParamContext context) throws ParamParseException {
        if (value == null || value.isBlank()) {
            throw new ParamParseException("trg requires a value.");
        }
        Pattern pattern = Pattern.compile(Pattern.quote(value.trim()), Pattern.CASE_INSENSITIVE);
        return new QueryPredicate.Eq("target", pattern);
    }

    @Override
    public List<String> suggestions(CommandSender sender, String input) {
        return List.of();
    }
}
