package net.medievalrp.spyglass.plugin.command.param;

import java.util.List;
import java.util.regex.Pattern;
import net.medievalrp.spyglass.api.query.QueryPredicate;

/**
 * Helper that expands a user-facing "match some text in an item field" query
 * into the five concrete Mongo paths that actually hold {@link
 * net.medievalrp.spyglass.api.event.StoredItem} values on different
 * record types. Kept here so the three {@code iname:} / {@code ilore:} /
 * {@code ench:} param handlers each stay a one-liner.
 */
final class ItemFieldParams {

    private static final List<String> ITEM_PATHS = List.of(
            "item",
            "beforeItem",
            "afterItem",
            "originalBlock.containerItems",
            "newBlock.containerItems");

    private ItemFieldParams() {
    }

    /** Compile a case-insensitive substring regex from raw user input. */
    static Pattern substringPattern(String raw) {
        return Pattern.compile(Pattern.quote(raw), Pattern.CASE_INSENSITIVE);
    }

    /**
     * Build an {@link QueryPredicate.Or} that matches when the given
     * sub-field of a {@link net.medievalrp.spyglass.api.event.StoredItem}
     * matches {@code pattern} on any of the record types that carry items.
     */
    static QueryPredicate anyItemField(String subField, Pattern pattern) {
        List<QueryPredicate> clauses = new java.util.ArrayList<>(ITEM_PATHS.size());
        for (String path : ITEM_PATHS) {
            clauses.add(new QueryPredicate.Eq(path + "." + subField, pattern));
        }
        return new QueryPredicate.Or(List.copyOf(clauses));
    }
}
