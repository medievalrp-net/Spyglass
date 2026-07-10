package net.medievalrp.spyglass.plugin.rollback;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.medievalrp.spyglass.api.query.QueryPredicate;
import org.jetbrains.annotations.ApiStatus;

/**
 * Extracts the materials an operator explicitly excluded with
 * {@code block:!x}, for the live-state guard (#264).
 *
 * <p>Walks the request's predicate tree: inside every {@link
 * QueryPredicate.Not}, any {@code Eq}/{@code In} over the {@code target}
 * or {@code containerType} fields names such a material. This covers
 * both the historical b: shape ({@code Not(Eq(target, X))}) and the
 * container-aware one ({@code Not(Or(And(Exists, Eq(containerType, X)),
 * And(Exists, Eq(target, X))))}, #263) without the parser having to
 * side-channel anything. Other params' excludes are unaffected: a:, p:,
 * c: and e: negate different fields.
 */
@ApiStatus.Internal
public final class ExcludedMaterials {

    private ExcludedMaterials() {
    }

    public static Set<String> of(List<QueryPredicate> predicates) {
        Set<String> out = new HashSet<>();
        for (QueryPredicate predicate : predicates) {
            collect(predicate, false, out);
        }
        return Set.copyOf(out);
    }

    private static void collect(QueryPredicate predicate, boolean negated, Set<String> out) {
        switch (predicate) {
            case QueryPredicate.Not not -> collect(not.predicate(), !negated, out);
            case QueryPredicate.And and -> and.predicates().forEach(p -> collect(p, negated, out));
            case QueryPredicate.Or or -> or.predicates().forEach(p -> collect(p, negated, out));
            case QueryPredicate.Eq eq -> {
                if (negated && isMaterialField(eq.field()) && eq.value() instanceof String s) {
                    out.add(s);
                }
            }
            case QueryPredicate.In in -> {
                if (negated && isMaterialField(in.field())) {
                    for (Object value : in.values()) {
                        if (value instanceof String s) {
                            out.add(s);
                        }
                    }
                }
            }
            case QueryPredicate.Range range -> {
                // ranges never name materials
            }
            case QueryPredicate.Exists exists -> {
                // existence guards carry no material
            }
        }
    }

    private static boolean isMaterialField(String field) {
        return "target".equals(field) || "containerType".equals(field);
    }
}
