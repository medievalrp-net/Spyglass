package net.medievalrp.spyglass.plugin.command.param;

import java.util.ArrayList;
import java.util.List;
import net.medievalrp.spyglass.api.param.ParamParseException;
import net.medievalrp.spyglass.api.param.QueryParamHandler;
import net.medievalrp.spyglass.api.query.QueryPredicate;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;

public final class BlockParam implements QueryParamHandler {

    @Override
    public List<String> aliases() {
        return List.of("b", "block");
    }

    @Override
    public QueryPredicate parse(String alias, String value, ParamContext context) throws ParamParseException {
        if (value == null || value.isBlank()) {
            throw new ParamParseException("Block parameter requires a material.");
        }
        // `!`-prefixed materials are excludes (#30). The prefix MUST be
        // stripped before Material.matchMaterial — Bukkit drops non-word
        // characters, so an unstripped "!chest" would silently parse as
        // CHEST and invert the operator's intent.
        List<String> includes = new ArrayList<>();
        List<String> excludes = new ArrayList<>();
        for (String raw : value.split(",")) {
            String token = raw.trim();
            if (token.isEmpty() || token.equals("!")) {
                continue;
            }
            boolean negated = token.startsWith("!");
            String name = negated ? token.substring(1) : token;
            Material material = Material.matchMaterial(name, false);
            if (material == null || !material.isBlock()) {
                throw new ParamParseException("Unknown block: " + name);
            }
            (negated ? excludes : includes).add(material.name());
        }
        if (includes.isEmpty() && excludes.isEmpty()) {
            throw new ParamParseException("Block parameter requires at least one material.");
        }
        List<QueryPredicate> clauses = new ArrayList<>(2);
        if (!includes.isEmpty()) {
            clauses.add(membership(includes));
        }
        if (!excludes.isEmpty()) {
            clauses.add(new QueryPredicate.Not(membership(excludes)));
        }
        return clauses.size() == 1 ? clauses.getFirst() : new QueryPredicate.And(clauses);
    }

    /**
     * {@code block:X} means the BLOCK (#263). On container transactions the
     * record's {@code target} is the moved ITEM and the container's own
     * material lives in {@code containerType}, so a bare target match made
     * {@code b:} an item filter (identical to {@code i:}) that could neither
     * find nor exclude a chest's transactions - and {@code block:!chest}
     * silently emptied the "protected" chest. Match {@code containerType}
     * where the record has one, {@code target} otherwise.
     *
     * <p>Both branches carry an {@link QueryPredicate.Exists} guard: on the
     * SQL backends {@code containerType} is a nullable column, and an
     * unguarded {@code Not(Or(...))} evaluates to NULL for every plain block
     * row under three-valued logic, silently dropping them from an exclude.
     */
    static QueryPredicate membership(List<String> names) {
        QueryPredicate onContainer = names.size() == 1
                ? new QueryPredicate.Eq("containerType", names.getFirst())
                : new QueryPredicate.In("containerType", names);
        QueryPredicate onTarget = names.size() == 1
                ? new QueryPredicate.Eq("target", names.getFirst())
                : new QueryPredicate.In("target", names);
        return new QueryPredicate.Or(List.of(
                new QueryPredicate.And(List.of(
                        new QueryPredicate.Exists("containerType", true), onContainer)),
                new QueryPredicate.And(List.of(
                        new QueryPredicate.Exists("containerType", false), onTarget))));
    }

    @Override
    public List<String> suggestions(CommandSender sender, String input) {
        String lower = input.toLowerCase(java.util.Locale.ROOT);
        return java.util.Arrays.stream(Material.values())
                .filter(Material::isBlock)
                .map(m -> m.name().toLowerCase(java.util.Locale.ROOT))
                .filter(name -> name.startsWith(lower))
                .limit(25)
                .toList();
    }
}
