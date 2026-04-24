package net.medievalrp.spyglass.plugin.command.param;

import java.util.ArrayList;
import java.util.List;
import net.medievalrp.spyglass.api.param.ParamParseException;
import net.medievalrp.spyglass.api.param.QueryParamHandler;
import net.medievalrp.spyglass.api.query.QueryPredicate;
import org.bukkit.command.CommandSender;

/**
 * {@code c:creeper} or {@code c:tnt,!environment} — match records whose
 * environment / entity source matches one of the given cause strings.
 * v1 stored this as a flat {@code Cause} string; v2 splits causes into
 * {@code source.description} (e.g. "fire-spread",
 * "block-explode:tnt") and {@code source.entityType} (e.g. "creeper",
 * "zombie"), so this handler fans the predicate across both.
 *
 * <p>Comma-separated values are an include list. Entries prefixed with
 * {@code !} are excluded. {@code c:creeper,!baby_zombie} matches
 * creeper-caused records but not baby-zombie-caused ones.
 */
public final class CauseParam implements QueryParamHandler {

    @Override
    public List<String> aliases() {
        return List.of("c", "cause");
    }

    @Override
    public QueryPredicate parse(String alias, String value, ParamContext context) throws ParamParseException {
        if (value == null || value.isBlank()) {
            throw new ParamParseException("c requires a value.");
        }
        List<String> includes = new ArrayList<>();
        List<String> excludes = new ArrayList<>();
        for (String raw : value.split(",")) {
            String trimmed = raw.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.startsWith("!")) {
                String tail = trimmed.substring(1);
                if (!tail.isEmpty()) {
                    excludes.add(tail);
                }
            } else {
                includes.add(trimmed);
            }
        }
        if (includes.isEmpty() && excludes.isEmpty()) {
            throw new ParamParseException("c requires at least one cause.");
        }
        List<QueryPredicate> clauses = new ArrayList<>(2);
        if (!includes.isEmpty()) {
            clauses.add(anyField(includes));
        }
        if (!excludes.isEmpty()) {
            clauses.add(new QueryPredicate.Not(anyField(excludes)));
        }
        if (clauses.size() == 1) {
            return clauses.getFirst();
        }
        return new QueryPredicate.And(clauses);
    }

    /**
     * Produces {@code source.description IN [v1...] OR source.entityType IN [v1...]}
     * so either kind of non-player source matches.
     */
    private static QueryPredicate anyField(List<String> values) {
        if (values.size() == 1) {
            String single = values.getFirst();
            return new QueryPredicate.Or(List.of(
                    new QueryPredicate.Eq("source.description", single),
                    new QueryPredicate.Eq("source.entityType", single)));
        }
        List<?> copy = List.copyOf(values);
        return new QueryPredicate.Or(List.of(
                new QueryPredicate.In("source.description", copy),
                new QueryPredicate.In("source.entityType", copy)));
    }

    @Override
    public List<String> suggestions(CommandSender sender, String input) {
        return List.of();
    }
}
