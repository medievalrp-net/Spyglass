package net.medievalrp.spyglass.api.query;

import java.util.EnumSet;
import java.util.List;

public record QueryRequest(
        List<QueryPredicate> predicates,
        Sort sort,
        int limit,
        EnumSet<Flag> flags,
        boolean grouping) {

    public QueryRequest {
        predicates = List.copyOf(predicates);
        flags = flags.clone();
    }
}
