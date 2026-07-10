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
            // $nor, not Filters.not: a top-level $not is rejected by the
            // server for compound children ("unknown top level operator:
            // $not") - first hit by the container-aware b: exclude (#263),
            // whose Not wraps an Or. A single-clause $nor is NOT for every
            // shape, simple or compound.
            case QueryPredicate.Not not -> Filters.nor(translate(not.predicate()));
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
        List<Bson> clauses = new ArrayList<>(4);
        if (range.lowerInclusive() != null) {
            clauses.add(Filters.gte(range.field(), range.lowerInclusive()));
        }
        if (range.upperInclusive() != null) {
            clauses.add(Filters.lte(range.field(), range.upperInclusive()));
        }
        // Let a block-coordinate range also seek the chunk-bucketed location
        // index by bounding the chunk field (cx = x>>4, cz = z>>4) with the
        // floor-divided coordinate range. The exact x/z clauses above still
        // filter within the seeked chunks, so results are identical; this just
        // adds a predicate the index can seek on. floorDiv(coord, 16) matches
        // BlockLocationCodec's coord >> 4, negatives included.
        String chunkField = chunkFieldFor(range.field());
        if (chunkField != null) {
            if (range.lowerInclusive() instanceof Number lower) {
                clauses.add(Filters.gte(chunkField, Math.floorDiv(lower.longValue(), 16)));
            }
            if (range.upperInclusive() instanceof Number upper) {
                clauses.add(Filters.lte(chunkField, Math.floorDiv(upper.longValue(), 16)));
            }
        }
        if (clauses.isEmpty()) {
            return Filters.empty();
        }
        if (clauses.size() == 1) {
            return clauses.getFirst();
        }
        return Filters.and(clauses);
    }

    private static String chunkFieldFor(String field) {
        if (RecordFields.LOCATION_X.equals(field)) {
            return RecordFields.LOCATION_CX;
        }
        if (RecordFields.LOCATION_Z.equals(field)) {
            return RecordFields.LOCATION_CZ;
        }
        return null;
    }
}
