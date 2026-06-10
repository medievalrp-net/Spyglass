package net.medievalrp.spyglass.plugin.storage;

import java.util.List;
import java.util.Objects;
import net.medievalrp.spyglass.api.event.EventRecord;
import net.medievalrp.spyglass.api.query.QueryPredicate;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * In-memory evaluation of a predicate tree against a single record —
 * the synthesized rolled-* entries never touch the store's filters, so
 * the caller's original predicates are applied here instead, restoring
 * exact filter parity with persisted receipt rows.
 *
 * <p>An unknown or absent field evaluates to {@code null}, which makes
 * {@code Eq}/{@code In}/{@code Range} fail and {@code Exists(false)}
 * pass — the same outcomes the stores produce for NULL columns, which
 * is what a persisted receipt row would have carried for fields a
 * {@code BlockUseRecord} doesn't have.
 */
@ApiStatus.Internal
final class PredicateEvaluator {

    private PredicateEvaluator() {
    }

    static boolean matchesAll(List<QueryPredicate> predicates, EventRecord record) {
        for (QueryPredicate predicate : predicates) {
            if (!matches(predicate, record)) {
                return false;
            }
        }
        return true;
    }

    static boolean matches(QueryPredicate predicate, EventRecord record) {
        return switch (predicate) {
            case QueryPredicate.Eq eq -> equalsValue(value(record, eq.field()), eq.value());
            case QueryPredicate.In in -> {
                Object actual = value(record, in.field());
                yield in.values().stream().anyMatch(expected -> equalsValue(actual, expected));
            }
            case QueryPredicate.Range range -> {
                Object actual = value(record, range.field());
                if (actual == null) {
                    yield false;
                }
                boolean lowerOk = range.lowerInclusive() == null
                        || compare(actual, range.lowerInclusive()) >= 0;
                boolean upperOk = range.upperInclusive() == null
                        || compare(actual, range.upperInclusive()) <= 0;
                yield lowerOk && upperOk;
            }
            case QueryPredicate.Exists exists ->
                    (value(record, exists.field()) != null) == exists.expected();
            case QueryPredicate.Not not -> !matches(not.predicate(), record);
            case QueryPredicate.And and -> matchesAll(and.predicates(), record);
            case QueryPredicate.Or or ->
                    or.predicates().stream().anyMatch(p -> matches(p, record));
        };
    }

    private static boolean equalsValue(@Nullable Object actual, @Nullable Object expected) {
        if (actual == null || expected == null) {
            return actual == null && expected == null;
        }
        if (actual instanceof Number a && expected instanceof Number b) {
            return a.doubleValue() == b.doubleValue();
        }
        return Objects.equals(actual, expected);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static int compare(Object actual, Object bound) {
        if (actual instanceof Number a && bound instanceof Number b) {
            return Double.compare(a.doubleValue(), b.doubleValue());
        }
        if (actual instanceof Comparable a && actual.getClass() == bound.getClass()) {
            return a.compareTo(bound);
        }
        // Incomparable shapes (e.g. Range over a UUID) — treat as
        // out-of-range rather than guessing.
        return Integer.MIN_VALUE;
    }

    private static @Nullable Object value(EventRecord record, String field) {
        return switch (field) {
            case "id" -> record.id();
            case "event" -> record.event();
            case "occurred" -> record.occurred();
            case "expiresAt" -> record.expiresAt();
            case "target" -> record.target();
            case "server" -> record.server();
            case "source.kind" -> record.source() == null ? null : record.source().kind();
            case "source.playerId" -> record.source() == null ? null : record.source().playerId();
            case "source.playerName" -> record.source() == null ? null : record.source().playerName();
            case "origin.kind" -> record.origin() == null ? null : record.origin().kind();
            case "origin.detail" -> record.origin() == null ? null : record.origin().detail();
            case "location.worldId" -> record.location() == null ? null : record.location().worldId();
            case "location.worldName" -> record.location() == null ? null : record.location().worldName();
            case "location.x" -> record.location() == null ? null : record.location().x();
            case "location.y" -> record.location() == null ? null : record.location().y();
            case "location.z" -> record.location() == null ? null : record.location().z();
            default -> null;
        };
    }

}
