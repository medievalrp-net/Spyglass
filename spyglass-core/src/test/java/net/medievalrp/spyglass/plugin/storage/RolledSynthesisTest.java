package net.medievalrp.spyglass.plugin.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import net.medievalrp.spyglass.api.event.BlockBreakRecord;
import net.medievalrp.spyglass.api.event.BlockSnapshot;
import net.medievalrp.spyglass.api.event.BlockUseRecord;
import net.medievalrp.spyglass.api.event.EventRecord;
import net.medievalrp.spyglass.api.event.Origin;
import net.medievalrp.spyglass.api.event.RollbackOpRecord;
import net.medievalrp.spyglass.api.event.Source;
import net.medievalrp.spyglass.api.query.Flag;
import net.medievalrp.spyglass.api.query.QueryPredicate;
import net.medievalrp.spyglass.api.query.QueryRequest;
import net.medievalrp.spyglass.api.query.QueryResult;
import net.medievalrp.spyglass.api.util.BlockLocation;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

class RolledSynthesisTest {

    private static final UUID WORLD = UUID.fromString("77777777-7777-7777-7777-777777777777");
    private static final UUID GRIEFER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final Instant GRIEF_TIME = Instant.parse("2026-06-10T10:00:00Z");
    private static final Instant OP_TIME = Instant.parse("2026-06-10T11:00:00Z");

    /**
     * Minimal in-memory store: filters with the same PredicateEvaluator
     * the synthesis uses for its own post-filter, which is exactly the
     * fidelity contract under test.
     */
    private static final class MemoryStore implements RecordStore {
        final List<EventRecord> rows = new ArrayList<>();

        @Override
        public void save(List<EventRecord> records) {
            rows.addAll(records);
        }

        @Override
        public QueryResult query(QueryRequest request) {
            List<EventRecord> matched = rows.stream()
                    .filter(r -> PredicateEvaluator.matchesAll(request.predicates(), r))
                    .limit(request.limit())
                    .toList();
            return new QueryResult(matched, List.of());
        }

        @Override
        public void close() {
        }
    }

    private static BlockBreakRecord griefBreak(int x) {
        BlockSnapshot stone = new BlockSnapshot(Material.STONE, "minecraft:stone",
                List.of(), List.of(), List.of(), List.of(), null);
        BlockSnapshot air = new BlockSnapshot(Material.AIR, "minecraft:air",
                List.of(), List.of(), List.of(), List.of(), null);
        return new BlockBreakRecord(UUID.randomUUID(), "break",
                GRIEF_TIME, GRIEF_TIME.plusSeconds(86400),
                Origin.player(), Source.player(GRIEFER, "Griefer"),
                new BlockLocation(WORLD, "world", x, 64, 0),
                "test", "STONE", stone, air);
    }

    private static RollbackOpRecord op(QueryRequest covered, String mode, Instant ceiling) {
        String blob = UndoReferenceBson.encodeBase64(covered, mode, ceiling,
                List.of(new UndoReferenceBson.WorldBox(WORLD, 0, 64, 0, 10, 64, 0)), 2, 0);
        return new RollbackOpRecord(UUID.randomUUID(), "rollback-op",
                OP_TIME, OP_TIME.plusSeconds(86400),
                Origin.rollback("Operator"), Source.player(UUID.randomUUID(), "Operator"),
                new BlockLocation(WORLD, "world", 0, 64, 0), "test",
                mode, blob);
    }

    private static QueryRequest request(QueryPredicate... predicates) {
        return new QueryRequest(List.of(predicates), net.medievalrp.spyglass.api.query.Sort.NEWEST_FIRST,
                100, EnumSet.noneOf(Flag.class), false);
    }

    private static QueryRequest griefQuery() {
        return request(new QueryPredicate.Eq("source.playerId", GRIEFER),
                new QueryPredicate.Eq("event", "break"));
    }

    @Test
    void synthesizesRolledPlaceMirroringEngineReceipts() {
        MemoryStore store = new MemoryStore();
        store.save(List.of(griefBreak(1), griefBreak(2)));
        store.save(List.of(op(griefQuery(), "ROLLBACK", OP_TIME)));

        List<EventRecord> rolled = new RolledSynthesis(store)
                .synthesize(request(new QueryPredicate.Eq("location.worldId", WORLD)));

        assertThat(rolled).hasSize(2);
        for (EventRecord r : rolled) {
            assertThat(r).isInstanceOf(BlockUseRecord.class);
            // Rollback of a break re-places the block → rolled-place of
            // the restored material, stamped with the op's time and the
            // engine's receipt identity (environment ROLLBACK source,
            // rollback origin naming the operator).
            assertThat(r.event()).isEqualTo("rolled-place");
            assertThat(r.target()).isEqualTo("STONE");
            assertThat(r.occurred()).isEqualTo(OP_TIME);
            assertThat(r.source().kind()).isEqualTo(Source.ENVIRONMENT);
            assertThat(r.source().description()).isEqualTo("ROLLBACK");
            assertThat(r.origin().kind()).isEqualTo("rollback");
            assertThat(r.origin().detail()).isEqualTo("Operator");
        }
    }

    @Test
    void restoreModeSynthesizesRolledBreak() {
        MemoryStore store = new MemoryStore();
        store.save(List.of(griefBreak(1)));
        store.save(List.of(op(griefQuery(), "RESTORE", OP_TIME)));

        List<EventRecord> rolled = new RolledSynthesis(store)
                .synthesize(request(new QueryPredicate.Eq("location.worldId", WORLD)));

        // Restoring a break re-applies it: the block becomes air.
        assertThat(rolled).hasSize(1);
        assertThat(rolled.get(0).event()).isEqualTo("rolled-break");
        assertThat(rolled.get(0).target()).isEqualTo("AIR");
    }

    @Test
    void ceilingExcludesRecordsAfterTheOperation() {
        MemoryStore store = new MemoryStore();
        store.save(List.of(griefBreak(1)));
        // Ceiling BEFORE the grief: the op never covered it.
        store.save(List.of(op(griefQuery(), "ROLLBACK", GRIEF_TIME.minusSeconds(60))));

        assertThat(new RolledSynthesis(store)
                .synthesize(request(new QueryPredicate.Eq("location.worldId", WORLD))))
                .isEmpty();
    }

    @Test
    void requestPredicatesApplyToTheSynthesizedRecord() {
        MemoryStore store = new MemoryStore();
        store.save(List.of(griefBreak(1)));
        store.save(List.of(op(griefQuery(), "ROLLBACK", OP_TIME)));
        RolledSynthesis synthesis = new RolledSynthesis(store);

        // Event filter: a:rolled-place matches, a:break must not invoke
        // synthesis at all (the persisted breaks answer that query).
        assertThat(synthesis.synthesize(request(
                new QueryPredicate.Eq("event", "rolled-place")))).hasSize(1);
        assertThat(synthesis.synthesize(request(
                new QueryPredicate.Eq("event", "break")))).isEmpty();

        // Time window applies to the OP time, not the grief time —
        // exactly how persisted receipts behaved.
        assertThat(synthesis.synthesize(request(
                new QueryPredicate.Range("occurred",
                        OP_TIME.minusSeconds(60), OP_TIME.plusSeconds(60))))).hasSize(1);
        assertThat(synthesis.synthesize(request(
                new QueryPredicate.Range("occurred",
                        GRIEF_TIME.minusSeconds(60), GRIEF_TIME.plusSeconds(60))))).isEmpty();

        // Location narrowing reaches the expansion query.
        assertThat(synthesis.synthesize(request(
                new QueryPredicate.Eq("location.x", 1)))).hasSize(1);
        assertThat(synthesis.synthesize(request(
                new QueryPredicate.Eq("location.x", 99)))).isEmpty();
    }

    @Test
    void synthesizedIdsAreDeterministicAcrossSearches() {
        MemoryStore store = new MemoryStore();
        store.save(List.of(griefBreak(1)));
        store.save(List.of(op(griefQuery(), "ROLLBACK", OP_TIME)));
        RolledSynthesis synthesis = new RolledSynthesis(store);
        QueryRequest search = request(new QueryPredicate.Eq("location.worldId", WORLD));

        UUID first = synthesis.synthesize(search).get(0).id();
        UUID second = synthesis.synthesize(search).get(0).id();
        assertThat(first).isEqualTo(second);
    }
}
