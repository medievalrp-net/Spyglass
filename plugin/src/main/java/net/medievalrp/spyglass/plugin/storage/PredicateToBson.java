package net.medievalrp.spyglass.plugin.storage;

import com.mongodb.client.model.Filters;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import net.medievalrp.spyglass.api.query.QueryPredicate;
import org.bson.conversions.Bson;

final class PredicateToBson {

    Bson translate(List<QueryPredicate> predicates) {
        if (predicates.isEmpty()) {
            return Filters.empty();
        }
        if (predicates.size() == 1) {
            return translate(predicates.get(0));
        }
        return Filters.and(predicates.stream().map(this::translate).toList());
    }

    Bson translate(QueryPredicate predicate) {
        return switch (predicate) {
            case QueryPredicate.Eq eq -> translateEq(eq.field(), eq.value());
            case QueryPredicate.In in -> Filters.in(in.field(), new ArrayList<>(in.values()));
            case QueryPredicate.Range range -> translateRange(range);
            case QueryPredicate.Exists exists -> Filters.exists(exists.field(), exists.expected());
            case QueryPredicate.Not not -> Filters.not(translate(not.predicate()));
            case QueryPredicate.And and -> Filters.and(and.predicates().stream().map(this::translate).toList());
            case QueryPredicate.Or or -> Filters.or(or.predicates().stream().map(this::translate).toList());
        };
    }

    private Bson translateEq(String field, Object value) {
        if (value instanceof Pattern pattern) {
            return Filters.regex(field, pattern);
        }
        return Filters.eq(field, value);
    }

    private Bson translateRange(QueryPredicate.Range range) {
        List<Bson> clauses = new ArrayList<>(2);
        if (range.lowerInclusive() != null) {
            clauses.add(Filters.gte(range.field(), range.lowerInclusive()));
        }
        if (range.upperInclusive() != null) {
            clauses.add(Filters.lte(range.field(), range.upperInclusive()));
        }
        if (clauses.isEmpty()) {
            return Filters.empty();
        }
        if (clauses.size() == 1) {
            return clauses.getFirst();
        }
        return Filters.and(clauses);
    }
}
