package net.medievalrp.spyglass.plugin.storage;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import net.medievalrp.spyglass.api.query.QueryPredicate;
import org.jetbrains.annotations.ApiStatus;

/**
 * Translates a list of {@link QueryPredicate} into a self-contained
 * SQL WHERE fragment for ClickHouse — values are inlined as CH
 * literals (no parameter binding).
 *
 * <p>Mirrors the shape of {@link PredicateToBson}: each predicate
 * kind has a dedicated translator, top-level list composition uses
 * {@code AND}. Inlining instead of binding lets us use client-v2's
 * {@code queryAll()} directly without going through the slower JDBC
 * read path.
 *
 * <p>Field path resolution goes through {@link ClickHouseFieldMapper}.
 * Any path the mapper doesn't know throws
 * {@link UnsupportedPredicateException}.
 */
@ApiStatus.Internal
final class PredicateToSql {

    private static final DateTimeFormatter CH_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    static final class UnsupportedPredicateException extends RuntimeException {
        UnsupportedPredicateException(String message) {
            super(message);
        }
    }

    String translate(List<QueryPredicate> predicates) {
        if (predicates.isEmpty()) {
            return "";
        }
        if (predicates.size() == 1) {
            return translate(predicates.get(0));
        }
        StringBuilder sql = new StringBuilder("(");
        for (int i = 0; i < predicates.size(); i++) {
            if (i > 0) {
                sql.append(" AND ");
            }
            sql.append(translate(predicates.get(i)));
        }
        sql.append(")");
        return sql.toString();
    }

    private String translate(QueryPredicate predicate) {
        return switch (predicate) {
            case QueryPredicate.Eq eq -> translateEq(eq.field(), eq.value());
            case QueryPredicate.In in -> translateIn(in.field(), in.values());
            case QueryPredicate.Range range -> translateRange(range);
            case QueryPredicate.Exists exists -> translateExists(exists.field(), exists.expected());
            case QueryPredicate.Not not -> "(NOT " + translate(not.predicate()) + ")";
            case QueryPredicate.And and -> translateGroup("AND", and.predicates());
            case QueryPredicate.Or or -> translateGroup("OR", or.predicates());
        };
    }

    private String translateGroup(String op, List<QueryPredicate> children) {
        if (children.isEmpty()) {
            return "AND".equals(op) ? "1=1" : "1=0";
        }
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < children.size(); i++) {
            if (i > 0) {
                sb.append(" ").append(op).append(" ");
            }
            sb.append(translate(children.get(i)));
        }
        sb.append(")");
        return sb.toString();
    }

    private String translateEq(String field, Object value) {
        String column = column(field);
        if (value instanceof Pattern pattern) {
            String rx = pattern.pattern();
            if ((pattern.flags() & Pattern.CASE_INSENSITIVE) != 0
                    && !rx.startsWith("(?i)")) {
                rx = "(?i)" + rx;
            }
            return "match(toString(" + column + "), " + stringLiteral(rx) + ")";
        }
        return column + " = " + literal(value);
    }

    private String translateIn(String field, List<?> values) {
        String column = column(field);
        if (values.isEmpty()) {
            return "1=0";
        }
        StringBuilder sb = new StringBuilder(column).append(" IN (");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(literal(values.get(i)));
        }
        sb.append(")");
        return sb.toString();
    }

    private String translateRange(QueryPredicate.Range range) {
        String column = column(range.field());
        List<String> clauses = new ArrayList<>(2);
        if (range.lowerInclusive() != null) {
            clauses.add(column + " >= " + literal(range.lowerInclusive()));
        }
        if (range.upperInclusive() != null) {
            clauses.add(column + " <= " + literal(range.upperInclusive()));
        }
        if (clauses.isEmpty()) {
            return "1=1";
        }
        if (clauses.size() == 1) {
            return clauses.get(0);
        }
        return "(" + String.join(" AND ", clauses) + ")";
    }

    private String translateExists(String field, boolean expected) {
        String column = column(field);
        return expected ? "(" + column + " IS NOT NULL)" : "(" + column + " IS NULL)";
    }

    private String column(String fieldPath) {
        String column = ClickHouseFieldMapper.columnFor(fieldPath);
        if (column == null) {
            // Two reasons a path can land here:
            //   1. Nested snapshot paths (item.name, beforeItem.lore, etc.)
            //      that genuinely live inside opaque BSON blobs on CH.
            //   2. A flat column we forgot to register in
            //      ClickHouseFieldMapper.COLUMN — caller-visible as
            //      "field X cannot be filtered" even though the column
            //      exists on disk. Spell out both so the operator can
            //      tell which side of the line they're on.
            boolean looksNested = fieldPath.contains(".")
                    && (fieldPath.startsWith("item.")
                            || fieldPath.startsWith("beforeItem.")
                            || fieldPath.startsWith("afterItem.")
                            || fieldPath.startsWith("originalBlock.")
                            || fieldPath.startsWith("newBlock."));
            String why = looksNested
                    ? "nested-snapshot paths live inside opaque BSON blobs and aren't searchable server-side"
                    : "no ClickHouse column is mapped for this path (add it to ClickHouseFieldMapper)";
            throw new UnsupportedPredicateException(
                    "ClickHouse backend cannot filter on field '" + fieldPath + "': " + why + ".");
        }
        return column;
    }

    /**
     * Format a Java value as a self-contained ClickHouse SQL literal.
     * Strings and timestamps go through {@link #stringLiteral} which
     * single-quotes and backslash-escapes; UUIDs are wrapped in
     * {@code toUUID(...)} to disambiguate the column type;
     * primitives stringify as-is.
     */
    private String literal(Object value) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof Instant instant) {
            return stringLiteral(CH_TIMESTAMP.format(instant.atOffset(ZoneOffset.UTC)));
        }
        if (value instanceof UUID uuid) {
            return "toUUID('" + uuid + "')";
        }
        if (value instanceof Number) {
            return value.toString();
        }
        if (value instanceof Boolean b) {
            return b ? "1" : "0";
        }
        return stringLiteral(value.toString());
    }

    private String stringLiteral(String value) {
        // CH SQL string escaping: backslash for backslash and quote.
        return "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }
}
