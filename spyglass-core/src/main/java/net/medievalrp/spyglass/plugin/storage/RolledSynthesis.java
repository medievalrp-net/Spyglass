package net.medievalrp.spyglass.plugin.storage;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.medievalrp.spyglass.api.event.BlockUseRecord;
import net.medievalrp.spyglass.api.event.EventRecord;
import net.medievalrp.spyglass.api.event.Origin;
import net.medievalrp.spyglass.api.event.RollbackOpRecord;
import net.medievalrp.spyglass.api.rollback.Rollbackable;
import net.medievalrp.spyglass.api.event.Source;
import net.medievalrp.spyglass.api.query.Flag;
import net.medievalrp.spyglass.api.query.QueryPredicate;
import net.medievalrp.spyglass.api.query.QueryRequest;
import net.medievalrp.spyglass.api.query.Sort;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Synthesizes the per-block {@code rolled-place} / {@code rolled-break}
 * entries searches show, from {@code rollback-op} records instead of
 * persisted receipt rows (#22).
 *
 * <p>A receipt is a materialized join: who/when live on the operation,
 * the block and restored material live on the original record the
 * operation covered, and coverage itself is "does the operation's
 * stored query match a record here at or before its ceiling". So for a
 * search request we (1) find candidate operations in the request's
 * time window, (2) re-run each operation's stored query narrowed to
 * the request's location constraints, (3) manufacture a virtual
 * receipt per covered original, mirroring exactly what the engine used
 * to persist — {@code Source.environment("ROLLBACK")},
 * {@code Origin.rollback(operator)}, the operation's timestamp, the
 * restored material as target — and (4) re-apply the caller's full
 * predicate tree in memory so filter behavior matches persisted rows.
 *
 * <p>Synthesized coverage is "the operation's query covered this
 * block"; persisted receipts recorded actual writes. The two differ
 * only for error-class skips, which every benchmark measured at zero.
 */
@ApiStatus.Internal
public final class RolledSynthesis {

    private static final Set<String> ROLLED_EVENTS = Set.of(
            "rolled-place", "rolled-break", "rolled-deposit", "rolled-withdraw");
    // Ops are rare (an operator action each); a search window with more
    // than this many is pathological — log-free clamp, newest first.
    private static final int MAX_OPS_PER_SEARCH = 256;

    private final RecordStore store;

    public RolledSynthesis(RecordStore store) {
        this.store = store;
    }

    /**
     * Virtual rolled records matching {@code request}, unsorted and
     * uncapped — the caller merges, sorts, and applies the limit.
     */
    public List<EventRecord> synthesize(QueryRequest request) {
        if (!eventFilterMightIncludeRolled(request.predicates())) {
            return List.of();
        }
        // Synthesized records always carry Source.environment("ROLLBACK");
        // a positive source.player* predicate can never match one — same
        // as the persisted receipts it emulates — so skip the op scan
        // entirely. This is what kept p:-filtered global searches from
        // grinding through one store query per op in the window (#33).
        if (!sourcePredicatesFeasible(request.predicates())) {
            return List.of();
        }
        List<EventRecord> out = new ArrayList<>();
        for (EventRecord candidate : findOps(request)) {
            // Expansion is one store query per op; once the caller's
            // limit is satisfiable the rest only add rows the merge
            // would truncate anyway. Newest ops expand first, matching
            // the default sort's bias.
            if (out.size() >= request.limit()) {
                break;
            }
            if (!(candidate instanceof RollbackOpRecord op) || op.reference() == null) {
                continue;
            }
            UndoReferenceBson.Reference ref;
            try {
                ref = UndoReferenceBson.decodeBase64(op.reference());
            } catch (RuntimeException ex) {
                continue; // unreadable blob — skip the op, never the search
            }
            if (!boxesIntersectRequest(ref, request.predicates())) {
                continue; // op landed entirely outside the searched area
            }
            expandOp(request, op, ref, out);
        }
        return out;
    }

    /**
     * False when the request's top-level predicates can never match a
     * synthesized record (which has playerId/playerName == null). Only
     * positive forms prune; Not(...) over a source predicate matches an
     * environment source and stays feasible.
     */
    private static boolean sourcePredicatesFeasible(List<QueryPredicate> predicates) {
        for (QueryPredicate predicate : predicates) {
            if (isPositiveSourcePlayerPredicate(predicate)) {
                return false;
            }
            if (predicate instanceof QueryPredicate.Or or
                    && !or.predicates().isEmpty()
                    && or.predicates().stream().allMatch(RolledSynthesis::isPositiveSourcePlayerPredicate)) {
                // p:<unresolved-name> parses to Or(byId, byName) — every
                // branch targets the player source, so none can match.
                return false;
            }
            if (predicate instanceof QueryPredicate.And and
                    && !sourcePredicatesFeasible(and.predicates())) {
                return false;
            }
        }
        return true;
    }

    private static boolean isPositiveSourcePlayerPredicate(QueryPredicate predicate) {
        String field = switch (predicate) {
            case QueryPredicate.Eq eq -> eq.field();
            case QueryPredicate.In in -> in.field();
            default -> null;
        };
        return field != null
                && (field.equals("source.playerId") || field.equals("source.playerName"));
    }

    /**
     * False when the request constrains location to a box that misses
     * every bounding box the operation recorded (v2 references). v1
     * references carry no boxes and always expand.
     */
    private static boolean boxesIntersectRequest(UndoReferenceBson.Reference ref,
                                                 List<QueryPredicate> predicates) {
        if (ref.boxes() == null || ref.boxes().isEmpty()) {
            return true;
        }
        long[] xRange = coordRange(predicates, "location.x");
        long[] zRange = coordRange(predicates, "location.z");
        if (xRange == null && zRange == null) {
            return true; // unbounded request — every op is in scope
        }
        for (UndoReferenceBson.WorldBox box : ref.boxes()) {
            boolean xOk = xRange == null
                    || (box.maxX() >= xRange[0] && box.minX() <= xRange[1]);
            boolean zOk = zRange == null
                    || (box.maxZ() >= zRange[0] && box.minZ() <= zRange[1]);
            if (xOk && zOk) {
                return true;
            }
        }
        return false;
    }

    private static long @Nullable [] coordRange(List<QueryPredicate> predicates, String field) {
        for (QueryPredicate predicate : predicates) {
            if (predicate instanceof QueryPredicate.Range range
                    && field.equals(range.field())
                    && range.lowerInclusive() instanceof Number lo
                    && range.upperInclusive() instanceof Number hi) {
                return new long[]{lo.longValue(), hi.longValue()};
            }
            if (predicate instanceof QueryPredicate.And and) {
                long[] nested = coordRange(and.predicates(), field);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    private List<EventRecord> findOps(QueryRequest request) {
        List<QueryPredicate> predicates = new ArrayList<>();
        predicates.add(new QueryPredicate.Eq("event", "rollback-op"));
        Instant[] window = timeWindow(request.predicates());
        if (window != null) {
            predicates.add(new QueryPredicate.Range("occurred", window[0], window[1]));
        }
        QueryRequest opQuery = new QueryRequest(predicates, Sort.NEWEST_FIRST,
                MAX_OPS_PER_SEARCH, EnumSet.of(Flag.NO_GROUP), false);
        return store.query(opQuery).records();
    }

    private void expandOp(QueryRequest request, RollbackOpRecord op,
                          UndoReferenceBson.Reference ref, List<EventRecord> out) {
        // The op's stored query identifies what it covered; the
        // request's location constraints narrow the expansion to the
        // searched spot; the ceiling keeps later events (including the
        // op's own audit trail) out of scope.
        List<QueryPredicate> expand = new ArrayList<>(ref.request().predicates());
        expand.add(new QueryPredicate.Range("occurred", Instant.EPOCH, ref.ceiling()));
        expand.addAll(locationPredicates(request.predicates()));
        QueryRequest verify = new QueryRequest(expand, Sort.NEWEST_FIRST,
                Math.max(request.limit(), 1), EnumSet.of(Flag.NO_GROUP), false);

        boolean rollback = "ROLLBACK".equalsIgnoreCase(ref.mode());
        String operator = op.source() == null ? null : op.source().playerName();
        Source source = Source.environment("ROLLBACK");
        Origin origin = Origin.rollback(operator == null ? "console" : operator);
        for (EventRecord original : store.query(verify).records()) {
            if (!(original instanceof Rollbackable rollbackable)) {
                continue;
            }
            EventRecord virtual = toRolled(op, rollback, source, origin, original, rollbackable);
            if (virtual != null && PredicateEvaluator.matchesAll(request.predicates(), virtual)) {
                out.add(virtual);
            }
        }
    }

    private static @Nullable EventRecord toRolled(RollbackOpRecord op, boolean rollback,
                                                  Source source, Origin origin,
                                                  EventRecord original, Rollbackable rollbackable) {
        var effect = rollback ? rollbackable.rollbackEffect() : rollbackable.restoreEffect();
        // Deterministic id: the same op covering the same record must
        // synthesize the same identity across repeated searches.
        UUID id = UUID.nameUUIDFromBytes(
                (op.id() + ":" + original.id()).getBytes(StandardCharsets.UTF_8));
        if (effect instanceof net.medievalrp.spyglass.api.rollback.RollbackEffect.ContainerSlotWrite csw) {
            // A rollback that reverted a container transaction used to leave
            // NO audit entry in either mode (#265). Synthesize the inverse
            // transaction: clearing a slot is rolled-withdraw of what sat
            // there, repopulating one is rolled-deposit of what came back.
            var placed = csw.replacement();
            var removed = csw.expectedCurrent();
            String event = placed == null ? "rolled-withdraw" : "rolled-deposit";
            String target = placed != null ? placed.material()
                    : removed != null ? removed.material() : "UNKNOWN";
            return new BlockUseRecord(id, event, op.occurred(), op.expiresAt(),
                    origin, source, csw.location(), op.server(), target);
        }
        if (!(effect instanceof net.medievalrp.spyglass.api.rollback.RollbackEffect.BlockReplace replace)) {
            // Entity effects still have no receipt shape.
            return null;
        }
        var after = replace.replacement();
        var destroyed = replace.expectedCurrent();
        boolean toAir = after == null
                || "AIR".equals(after.material() == null ? "AIR" : after.material().name());
        String event = toAir ? "rolled-break" : "rolled-place";
        // rolled-break names what was DESTROYED, not the air that replaced
        // it - "ROLLBACK broke AIR" identified nothing (#269). rolled-place
        // keeps naming what was placed.
        String target;
        if (toAir) {
            target = destroyed == null || destroyed.material() == null
                    ? "AIR" : destroyed.material().name();
        } else {
            target = after.material() == null ? "AIR" : after.material().name();
        }
        return new BlockUseRecord(id, event, op.occurred(), op.expiresAt(),
                origin, source, replace.location(), op.server(), target);
    }

    // --- request introspection (flat lists + And nesting; Or/Not are
    // handled conservatively — they widen candidacy/expansion and the
    // in-memory evaluator restores exactness afterwards) ---

    private static boolean eventFilterMightIncludeRolled(List<QueryPredicate> predicates) {
        for (QueryPredicate predicate : predicates) {
            if (predicate instanceof QueryPredicate.Eq eq && "event".equals(eq.field())) {
                return eq.value() instanceof String s && ROLLED_EVENTS.contains(s);
            }
            if (predicate instanceof QueryPredicate.In in && "event".equals(in.field())) {
                return in.values().stream().anyMatch(ROLLED_EVENTS::contains);
            }
            if (predicate instanceof QueryPredicate.And and
                    && !eventFilterMightIncludeRolled(and.predicates())) {
                return false;
            }
        }
        return true;
    }

    private static @Nullable Instant[] timeWindow(List<QueryPredicate> predicates) {
        for (QueryPredicate predicate : predicates) {
            if (predicate instanceof QueryPredicate.Range range
                    && "occurred".equals(range.field())
                    && range.lowerInclusive() instanceof Instant from
                    && range.upperInclusive() instanceof Instant to) {
                return new Instant[]{from, to};
            }
            if (predicate instanceof QueryPredicate.And and) {
                Instant[] nested = timeWindow(and.predicates());
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    private static List<QueryPredicate> locationPredicates(List<QueryPredicate> predicates) {
        List<QueryPredicate> out = new ArrayList<>();
        for (QueryPredicate predicate : predicates) {
            String field = switch (predicate) {
                case QueryPredicate.Eq eq -> eq.field();
                case QueryPredicate.In in -> in.field();
                case QueryPredicate.Range range -> range.field();
                case QueryPredicate.Exists exists -> exists.field();
                default -> null;
            };
            if (field != null && field.startsWith("location.")) {
                out.add(predicate);
            } else if (predicate instanceof QueryPredicate.And and) {
                out.addAll(locationPredicates(and.predicates()));
            }
        }
        return out;
    }
}
