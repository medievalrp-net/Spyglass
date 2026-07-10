package net.medievalrp.spyglass.plugin.command.param;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.medievalrp.spyglass.api.param.ParamParseException;
import net.medievalrp.spyglass.api.param.QueryParamHandler;
import net.medievalrp.spyglass.api.query.QueryPredicate;
import org.bukkit.command.CommandSender;

/**
 * {@code trg:} takes two value shapes:
 *
 * <ul>
 *   <li>{@code trg:x,y,z} - exact block coordinates. Pins the query to
 *       that single cell (and suppresses the default radius via the
 *       parser's location-bound detection), which is what COMMANDS.md
 *       and the inspection-wand hint always promised (#305).</li>
 *   <li>{@code trg:chest} - case-insensitive substring regex against
 *       the record's {@code target} field. General-purpose fallback for
 *       when the typed params ({@code b:}, {@code i:}, {@code e:})
 *       don't quite fit what the operator remembers about the record.</li>
 * </ul>
 *
 * <p>The substring regex is anchored as a {@code Pattern.quote}-wrapped
 * substring, so user input is always treated as literal text rather
 * than a regex fragment.
 */
public final class TargetParam implements QueryParamHandler {

    private static final Pattern COORDS =
            Pattern.compile("^(-?\\d{1,8})\\s*,\\s*(-?\\d{1,8})\\s*,\\s*(-?\\d{1,8})$");

    @Override
    public List<String> aliases() {
        return List.of("trg", "target");
    }

    @Override
    public QueryPredicate parse(String alias, String value, ParamContext context) throws ParamParseException {
        if (value == null || value.isBlank()) {
            throw new ParamParseException("trg requires a value.");
        }
        String trimmed = value.trim();
        Matcher coords = COORDS.matcher(trimmed);
        if (coords.matches()) {
            int x = Integer.parseInt(coords.group(1));
            int y = Integer.parseInt(coords.group(2));
            int z = Integer.parseInt(coords.group(3));
            // Point ranges ride the same store machinery as the r:/-we
            // cuboids, so every backend already understands them.
            return new QueryPredicate.And(List.of(
                    new QueryPredicate.Range("location.x", x, x),
                    new QueryPredicate.Range("location.y", y, y),
                    new QueryPredicate.Range("location.z", z, z)));
        }
        Pattern pattern = Pattern.compile(Pattern.quote(trimmed), Pattern.CASE_INSENSITIVE);
        return new QueryPredicate.Eq("target", pattern);
    }

    @Override
    public List<String> suggestions(CommandSender sender, String input) {
        return List.of();
    }
}
