package net.medievalrp.spyglass.plugin.storage;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import net.medievalrp.spyglass.api.query.QueryPredicate;
import net.medievalrp.spyglass.api.util.EventIds;
import org.jetbrains.annotations.ApiStatus;

/**
 * Translates a list of {@link QueryPredicate} into a self-contained SQL
 * WHERE fragment for the {@link SqliteRecordStore} schema.
 *
 * <p>The SQLite schema is palette-interned: high-cardinality strings
 * (event / world / player names, block data) live in a {@code dict}
 * table and UUIDs in a {@code uuids} table, with the {@code records}
 * row holding only the small integer reference. So unlike the
 * ClickHouse {@link PredicateToSql} — which inlines string/UUID literals
 * the column compares directly — this translator resolves each literal
 * to its palette id <em>up front</em> via {@link Palette} and inlines
 * that integer. Three consequences:
 *
 * <ul>
 *   <li>Every inlined literal is an {@code int}/{@code long}, so there
 *       is no SQL string-escaping surface at all — a hostile player name
 *       never reaches the SQL text, only its resolved row id (or "no
 *       such id", which compiles to a constant-false clause).</li>
 *   <li>A value that isn't in the palette yet (e.g. {@code p:Ghost} for a
 *       player with no records) resolves to no id, so the predicate is
 *       statically unsatisfiable and translates to {@code 0} — matching
 *       nothing, exactly as it should.</li>
 *   <li>A {@code regex} match against an interned column can't be pushed
 *       (the column is an int, not the string), so it raises
 *       {@link UnsupportedPredicateException} and the store evaluates it
 *       in memory against the decoded record (the #32 post-filter path,
 *       which still sees the palette-resolved field values).</li>
 * </ul>
 *
 * <p>A block-coordinate range additionally bounds the chunk-bucket
 * column ({@code cx = x>>4}, {@code cz = z>>4}) so the region rollback
 * index can seek — mirroring {@link PredicateToBson} and the Mongo #100
 * work. The exact x/z clauses still filter within the seeked chunks, so
 * results are identical.
 */
@ApiStatus.Internal
final class SqlitePredicateToSql {

    /** Read-side palette resolution. {@code null} means "not interned". */
    interface Palette {
        Integer dictId(String value);

        Integer uuidId(UUID value);
    }

    static final class UnsupportedPredicateException extends RuntimeException {
        UnsupportedPredicateException(String message) {
            super(message);
        }
    }

    /** How a column's literal is resolved before it's inlined. */
    private enum Kind {
        /** {@code seq} primary key — value is the {@link EventIds} sequence of a UUID. */
        SEQ,
        /** A {@code dict} string reference. */
        DICT,
        /** A {@code uuids} reference. */
        UUID_REF,
        /** Epoch-seconds timestamp column. */
        INSTANT_SEC,
        /** Plain signed integer column (coordinates). */
        INT
    }

    private record Col(String name, Kind kind) {
    }

    // Only the indexed / filterable scalar paths map to a column. Deep
    // paths into the per-event blob (item.name, message, beforeItem.lore,
    // source.entityType, …) have no column and raise Unsupported, so they
    // fall to the in-memory post-filter against the decoded record.
    private static final Map<String, Col> COLUMNS = Map.ofEntries(
            Map.entry("id", new Col("seq", Kind.SEQ)),
            Map.entry("event", new Col("event", Kind.DICT)),
            Map.entry("occurred", new Col("occurred", Kind.INSTANT_SEC)),
            Map.entry("origin.kind", new Col("origin_kind", Kind.DICT)),
            Map.entry("source.playerId", new Col("player", Kind.UUID_REF)),
            Map.entry("location.worldId", new Col("world", Kind.UUID_REF)),
            Map.entry("location.x", new Col("x", Kind.INT)),
            Map.entry("location.y", new Col("y", Kind.INT)),
            Map.entry("location.z", new Col("z", Kind.INT)),
            Map.entry("server", new Col("server", Kind.DICT)),
            Map.entry("target", new Col("target", Kind.DICT)));

    private final Palette palette;

    SqlitePredicateToSql(Palette palette) {
        this.palette = palette;
    }

    /** Translate a top-level AND list; empty input yields the empty string. */
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
        return sql.append(")").toString();
    }

    private String translate(QueryPredicate predicate) {
        return switch (predicate) {
            case QueryPredicate.Eq eq -> translateEq(col(eq.field()), eq.field(), eq.value());
            case QueryPredicate.In in -> translateIn(col(in.field()), in.values());
            case QueryPredicate.Range range -> translateRange(range);
            case QueryPredicate.Exists exists -> translateExists(col(exists.field()), exists.expected());
            case QueryPredicate.Not not -> "(NOT " + translate(not.predicate()) + ")";
            case QueryPredicate.And and -> translateGroup("AND", and.predicates());
            case QueryPredicate.Or or -> translateGroup("OR", or.predicates());
        };
    }

    private String translateGroup(String op, List<QueryPredicate> children) {
        if (children.isEmpty()) {
            return "AND".equals(op) ? "1" : "0";
        }
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < children.size(); i++) {
            if (i > 0) {
                sb.append(" ").append(op).append(" ");
            }
            sb.append(translate(children.get(i)));
        }
        return sb.append(")").toString();
    }

    private String translateEq(Col col, String field, Object value) {
        if (value instanceof Pattern) {
            // A regex can't run against an interned int column; evaluate it
            // in memory against the decoded record instead.
            throw new UnsupportedPredicateException(
                    "SQLite backend cannot push a regex match on interned column '" + field + "'.");
        }
        Long resolved = resolve(col, value);
        // A literal absent from the palette can't match any row.
        return resolved == null ? "0" : col.name() + " = " + resolved;
    }

    private String translateIn(Col col, List<?> values) {
        List<Long> ids = new ArrayList<>(values.size());
        for (Object value : values) {
            if (value instanceof Pattern) {
                throw new UnsupportedPredicateException(
                        "SQLite backend cannot push a regex match in an IN clause on '" + col.name() + "'.");
            }
            Long resolved = resolve(col, value);
            if (resolved != null) {
                ids.add(resolved);
            }
        }
        if (ids.isEmpty()) {
            return "0";
        }
        StringBuilder sb = new StringBuilder(col.name()).append(" IN (");
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(ids.get(i));
        }
        return sb.append(")").toString();
    }

    private String translateRange(QueryPredicate.Range range) {
        Col col = col(range.field());
        if (col.kind() != Kind.INSTANT_SEC && col.kind() != Kind.INT) {
            // Ranges only make sense on ordered numeric columns; an
            // interned-string/uuid range is meaningless.
            throw new UnsupportedPredicateException(
                    "SQLite backend cannot range over column '" + col.name() + "'.");
        }
        List<String> clauses = new ArrayList<>(4);
        Long lower = scalar(col, range.lowerInclusive());
        Long upper = scalar(col, range.upperInclusive());
        if (lower != null) {
            clauses.add(col.name() + " >= " + lower);
        }
        if (upper != null) {
            clauses.add(col.name() + " <= " + upper);
        }
        // Let a block-coordinate range also seek the chunk-bucketed location
        // index by bounding its chunk EXPRESSION (x>>4 / z>>4) — the exact
        // expression idx_loc is built on, so the planner can use it. The
        // chunk isn't a stored column; SQLite computes it. floorDiv(coord,16)
        // matches the >>4 the index uses, negatives included.
        String chunkExpr = chunkExpressionFor(col.name());
        if (chunkExpr != null) {
            if (range.lowerInclusive() instanceof Number n) {
                clauses.add(chunkExpr + " >= " + Math.floorDiv(n.longValue(), 16));
            }
            if (range.upperInclusive() instanceof Number n) {
                clauses.add(chunkExpr + " <= " + Math.floorDiv(n.longValue(), 16));
            }
        }
        if (clauses.isEmpty()) {
            return "1";
        }
        if (clauses.size() == 1) {
            return clauses.get(0);
        }
        return "(" + String.join(" AND ", clauses) + ")";
    }

    private String translateExists(Col col, boolean expected) {
        return expected ? "(" + col.name() + " IS NOT NULL)" : "(" + col.name() + " IS NULL)";
    }

    private static String chunkExpressionFor(String column) {
        if ("x".equals(column)) {
            return "(x >> 4)";
        }
        if ("z".equals(column)) {
            return "(z >> 4)";
        }
        return null;
    }

    // Resolve an equality/IN literal to the long that the column stores.
    // Returns null when a palette literal isn't interned (no such row).
    private Long resolve(Col col, Object value) {
        return switch (col.kind()) {
            case SEQ -> value instanceof UUID uuid
                    ? EventIds.sequenceOf(uuid)
                    : ((Number) value).longValue();
            case INT -> ((Number) value).longValue();
            case INSTANT_SEC -> ((Instant) value).getEpochSecond();
            case DICT -> {
                Integer id = palette.dictId(String.valueOf(value));
                yield id == null ? null : id.longValue();
            }
            case UUID_REF -> {
                Integer id = palette.uuidId((UUID) value);
                yield id == null ? null : id.longValue();
            }
        };
    }

    // Scalar bound for a range — only INSTANT_MS / INT reach here.
    private static Long scalar(Col col, Object bound) {
        if (bound == null) {
            return null;
        }
        return col.kind() == Kind.INSTANT_SEC
                ? ((Instant) bound).getEpochSecond()
                : ((Number) bound).longValue();
    }

    private static Col col(String fieldPath) {
        Col col = COLUMNS.get(fieldPath);
        if (col == null) {
            throw new UnsupportedPredicateException(
                    "SQLite backend cannot filter on field '" + fieldPath
                            + "': it lives in the per-event blob and isn't a column "
                            + "(evaluated in memory against the decoded record instead).");
        }
        return col;
    }
}
