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
 * WHERE fragment for the {@link MariaDbRecordStore} schema.
 *
 * <p>Structurally identical to {@link SqlitePredicateToSql} - the MariaDB
 * store reuses the same palette-interned, hybrid schema, so high-cardinality
 * strings / UUIDs are resolved to their small integer palette id <em>up
 * front</em> via {@link Palette} and that integer is inlined. The same three
 * consequences hold: no SQL string-escaping surface (only resolved ints reach
 * the SQL text), an un-interned literal compiles to a constant-false {@code 0},
 * and a regex against an interned column raises {@link
 * UnsupportedPredicateException} so the store post-filters it in memory.
 *
 * <p>The one dialect difference from the SQLite translator is the chunk
 * bucket. SQLite seeks its location index with an inline expression
 * ({@code x>>4}); MariaDB / MySQL can't index an inline expression portably,
 * so {@link MariaDbRecordStore} materializes the bucket as virtual generated
 * columns {@code cx = FLOOR(x/16)} / {@code cz = FLOOR(z/16)} and indexes
 * those. So a coordinate range bounds {@code cx} / {@code cz} directly (a
 * plain column compare the planner seeks), with the bound computed by
 * {@link Math#floorDiv}, which matches {@code FLOOR(coord/16)} for negatives
 * too. The exact x/z clauses still filter within the seeked chunks, so results
 * are identical.
 */
@ApiStatus.Internal
final class MariaDbPredicateToSql {

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
        /** {@code seq} primary key - value is the {@link EventIds} sequence of a UUID. */
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

    // Only the indexed / filterable scalar paths map to a column. Deep paths
    // into the per-event payload (item.name, message, beforeItem.lore,
    // source.entityType, ...) have no column and raise Unsupported, so they
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

    MariaDbPredicateToSql(Palette palette) {
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
                    "MariaDB backend cannot push a regex match on interned column '" + field + "'.");
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
                        "MariaDB backend cannot push a regex match in an IN clause on '" + col.name() + "'.");
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
                    "MariaDB backend cannot range over column '" + col.name() + "'.");
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
        // index by bounding its chunk column (cx / cz). Those are virtual
        // generated columns FLOOR(x/16) / FLOOR(z/16), so floorDiv(coord,16)
        // is the exact bucket, negatives included.
        String chunkCol = chunkColumnFor(col.name());
        if (chunkCol != null) {
            if (range.lowerInclusive() instanceof Number n) {
                clauses.add(chunkCol + " >= " + Math.floorDiv(n.longValue(), 16));
            }
            if (range.upperInclusive() instanceof Number n) {
                clauses.add(chunkCol + " <= " + Math.floorDiv(n.longValue(), 16));
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

    private static String chunkColumnFor(String column) {
        if ("x".equals(column)) {
            return "cx";
        }
        if ("z".equals(column)) {
            return "cz";
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

    // Scalar bound for a range - only INSTANT_SEC / INT reach here.
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
                    "MariaDB backend cannot filter on field '" + fieldPath
                            + "': it lives in the per-event payload and isn't a column "
                            + "(evaluated in memory against the decoded record instead).");
        }
        return col;
    }
}
