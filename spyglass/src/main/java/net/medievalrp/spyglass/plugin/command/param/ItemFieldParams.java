package net.medievalrp.spyglass.plugin.command.param;

import java.util.List;
import java.util.regex.Pattern;
import net.medievalrp.spyglass.api.param.ParamParseException;
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

    /**
     * Cap on free-text search terms. v1 had a strict char-class whitelist;
     * we keep {@link Pattern#quote} to defang regex metachars but layer on
     * a length cap and control-char reject so a player who notices the
     * search engine is a regex can't ship megabyte payloads or null-byte
     * fingerprints into it.
     */
    static final int MAX_TERM_LEN = 256;

    private ItemFieldParams() {
    }

    /**
     * Reject obviously-malicious or oversized search input before it
     * reaches the regex compiler. Caller is expected to have already
     * trimmed; {@code alias} is included in the error so the operator
     * sees which param failed.
     */
    static String requireSafeTerm(String alias, String trimmed) throws ParamParseException {
        if (trimmed.length() > MAX_TERM_LEN) {
            throw new ParamParseException(
                    alias + " value is too long (max " + MAX_TERM_LEN + " chars).");
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                throw new ParamParseException(
                        alias + " value contains a control character.");
            }
        }
        return trimmed;
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

    /**
     * Build a predicate that matches whenever any of the item-bearing record
     * paths has at least one populated meta field — non-null custom name,
     * non-empty lore, or non-empty enchants. Mirrors v1's
     * {@code cu:y} semantic ("item has metadata") without depending on a
     * single boolean flag, since v2 always serializes the full {@code
     * StoredItem} record.
     */
    static QueryPredicate hasMetadata() {
        List<QueryPredicate> clauses = new java.util.ArrayList<>(ITEM_PATHS.size() * 3);
        for (String path : ITEM_PATHS) {
            // name: non-null custom display name
            clauses.add(new QueryPredicate.Not(new QueryPredicate.Eq(path + ".name", null)));
            // lore.0 / enchants.0 exist <=> the array is non-empty
            clauses.add(new QueryPredicate.Exists(path + ".lore.0", true));
            clauses.add(new QueryPredicate.Exists(path + ".enchants.0", true));
        }
        return new QueryPredicate.Or(List.copyOf(clauses));
    }
}
