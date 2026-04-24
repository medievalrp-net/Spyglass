package net.medievalrp.spyglass.api.query;

import java.util.List;

public sealed interface QueryPredicate permits
        QueryPredicate.Eq,
        QueryPredicate.In,
        QueryPredicate.Range,
        QueryPredicate.Exists,
        QueryPredicate.Not,
        QueryPredicate.And,
        QueryPredicate.Or {

    record Eq(String field, Object value) implements QueryPredicate {
    }

    record In(String field, List<?> values) implements QueryPredicate {
        public In {
            values = List.copyOf(values);
        }
    }

    record Range(String field, Object lowerInclusive, Object upperInclusive) implements QueryPredicate {
    }

    record Exists(String field, boolean expected) implements QueryPredicate {
    }

    record Not(QueryPredicate predicate) implements QueryPredicate {
    }

    record And(List<QueryPredicate> predicates) implements QueryPredicate {
        public And {
            predicates = List.copyOf(predicates);
        }
    }

    record Or(List<QueryPredicate> predicates) implements QueryPredicate {
        public Or {
            predicates = List.copyOf(predicates);
        }
    }
}
