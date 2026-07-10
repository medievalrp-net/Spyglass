package net.medievalrp.spyglass.plugin.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import net.medievalrp.spyglass.api.event.BlockPlaceRecord;
import net.medievalrp.spyglass.api.event.BlockSnapshot;
import net.medievalrp.spyglass.api.event.ContainerDepositRecord;
import net.medievalrp.spyglass.api.event.ContainerWithdrawRecord;
import net.medievalrp.spyglass.api.event.EventRecord;
import net.medievalrp.spyglass.api.event.Origin;
import net.medievalrp.spyglass.api.event.RollbackOpRecord;
import net.medievalrp.spyglass.api.event.Source;
import net.medievalrp.spyglass.api.event.StoredItem;
import net.medievalrp.spyglass.api.query.Flag;
import net.medievalrp.spyglass.api.query.QueryPredicate;
import net.medievalrp.spyglass.api.query.QueryRequest;
import net.medievalrp.spyglass.api.query.QueryResult;
import net.medievalrp.spyglass.api.query.Sort;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for the rolled-audit gaps (#265, #269) in the default
 * {@code synthesized} mode:
 *
 * <ul>
 * <li>#265 - a rollback that reverted container transactions used to
 *     synthesize NOTHING; it now yields rolled-withdraw / rolled-deposit
 *     entries, searchable via {@code a:}.</li>
 * <li>#269 - {@code rolled-break} used to name AIR (the block written),
 *     discarding the destroyed block's identity in the same expression
 *     that decided to call it a break; it now names what was destroyed.</li>
 * </ul>
 */
class RolledAuditRegressionTest {

    private static final UUID WORLD = UUID.fromString("77777777-7777-7777-7777-777777777777");
    private static final UUID GRIEFER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final Instant GRIEF_TIME = Instant.parse("2026-06-10T10:00:00Z");
    private static final Instant OP_TIME = Instant.parse("2026-06-10T11:00:00Z");

    private static final class MemoryStore implements RecordStore {
        final List<EventRecord> rows = new ArrayList<>();

        @Override
        public void save(List<EventRecord> records) {
            rows.addAll(records);
        }

        @Override
        public QueryResult query(QueryRequest request) {
            return new QueryResult(rows.stream()
                    .filter(r -> PredicateEvaluator.matchesAll(request.predicates(), r))
                    .limit(request.limit())
                    .toList(), List.of());
        }

        @Override
        public void close() {
        }
    }

    private static BlockSnapshot snapshot(Material material) {
        return new BlockSnapshot(material,
                "minecraft:" + material.name().toLowerCase(java.util.Locale.ROOT),
                List.of(), List.of(), List.of(), List.of(), null);
    }

    private static BlockPlaceRecord placedBarrelOnAir() {
        return new BlockPlaceRecord(UUID.randomUUID(), "place",
                GRIEF_TIME, GRIEF_TIME.plusSeconds(86400),
                Origin.player(), Source.player(GRIEFER, "Griefer"),
                new net.medievalrp.spyglass.api.util.BlockLocation(WORLD, "world", 2, 64, 0), "test", "BARREL",
                snapshot(Material.AIR), snapshot(Material.BARREL));
    }

    private static ContainerDepositRecord deposit() {
        return new ContainerDepositRecord(
                UUID.randomUUID(), "deposit", GRIEF_TIME, GRIEF_TIME.plusSeconds(86400),
                Origin.player(), Source.player(GRIEFER, "Griefer"),
                new net.medievalrp.spyglass.api.util.BlockLocation(WORLD, "world", 1, 64, 0), "test", "DIAMOND", "CHEST", 3, 64,
                null, new StoredItem(3, "DIAMOND", "minecraft:diamond"));
    }

    private static ContainerWithdrawRecord withdraw() {
        return new ContainerWithdrawRecord(
                UUID.randomUUID(), "withdraw", GRIEF_TIME, GRIEF_TIME.plusSeconds(86400),
                Origin.player(), Source.player(GRIEFER, "Griefer"),
                new net.medievalrp.spyglass.api.util.BlockLocation(WORLD, "world", 4, 64, 0), "test", "GOLD_INGOT", "CHEST", 5, 16,
                new StoredItem(5, "GOLD_INGOT", "minecraft:gold_ingot"), null);
    }

    private static RollbackOpRecord op(QueryRequest covered) {
        String blob = UndoReferenceBson.encodeBase64(covered, "ROLLBACK", OP_TIME,
                List.of(new UndoReferenceBson.WorldBox(WORLD, 0, 64, 0, 10, 64, 0)), 2, 0);
        return new RollbackOpRecord(UUID.randomUUID(), "rollback-op",
                OP_TIME, OP_TIME.plusSeconds(86400),
                Origin.rollback("Operator"), Source.player(UUID.randomUUID(), "Operator"),
                new net.medievalrp.spyglass.api.util.BlockLocation(WORLD, "world", 0, 64, 0),
                "test", "ROLLBACK", blob);
    }

    private static QueryRequest request(QueryPredicate... predicates) {
        return new QueryRequest(List.of(predicates), Sort.NEWEST_FIRST,
                100, EnumSet.of(Flag.NO_GROUP), false);
    }

    private static QueryRequest coveredBy(String event) {
        return request(new QueryPredicate.Eq("source.playerId", GRIEFER),
                new QueryPredicate.Eq("event", event));
    }

    // Since #287 a container transaction only ever reverts under
    // --containers, so an op that covered one always carries the flag;
    // synthesis refuses container receipts for unflagged ops (#302).
    private static QueryRequest coveredByWithContainers(String event) {
        return new QueryRequest(
                List.of(new QueryPredicate.Eq("source.playerId", GRIEFER),
                        new QueryPredicate.Eq("event", event)),
                Sort.NEWEST_FIRST, 100,
                EnumSet.of(Flag.NO_GROUP, Flag.INCLUDE_CONTAINERS), false);
    }

    @Test
    void rolledBreakNamesTheDestroyedBlock() {
        MemoryStore store = new MemoryStore();
        store.save(List.of(placedBarrelOnAir()));
        store.save(List.of(op(coveredBy("place"))));

        List<EventRecord> rolled = new RolledSynthesis(store)
                .synthesize(request(new QueryPredicate.Eq("location.worldId", WORLD)));

        assertThat(rolled).hasSize(1);
        assertThat(rolled.get(0).event()).isEqualTo("rolled-break");
        assertThat(rolled.get(0).target())
                .as("#269: the audit names the BARREL the rollback destroyed, not the AIR it wrote")
                .isEqualTo("BARREL");
    }

    @Test
    void containerRollbackSynthesizesRolledWithdraw() {
        MemoryStore store = new MemoryStore();
        store.save(List.of(deposit()));
        store.save(List.of(op(coveredByWithContainers("deposit"))));

        List<EventRecord> rolled = new RolledSynthesis(store)
                .synthesize(request(new QueryPredicate.Eq("location.worldId", WORLD)));

        assertThat(rolled)
                .as("#265: a rollback that reverted a deposit leaves an audit entry")
                .hasSize(1);
        assertThat(rolled.get(0).event()).isEqualTo("rolled-withdraw");
        assertThat(rolled.get(0).target()).isEqualTo("DIAMOND");
    }

    @Test
    void containerRestoreSynthesizesRolledDeposit() {
        MemoryStore store = new MemoryStore();
        store.save(List.of(withdraw()));
        store.save(List.of(op(coveredByWithContainers("withdraw"))));

        List<EventRecord> rolled = new RolledSynthesis(store)
                .synthesize(request(new QueryPredicate.Eq("location.worldId", WORLD)));

        assertThat(rolled).hasSize(1);
        assertThat(rolled.get(0).event())
                .as("rolling back a withdraw puts the stack back: rolled-deposit")
                .isEqualTo("rolled-deposit");
        assertThat(rolled.get(0).target()).isEqualTo("GOLD_INGOT");
    }

    @Test
    void rolledContainerEventsAreSearchableByEventFilter() {
        MemoryStore store = new MemoryStore();
        store.save(List.of(deposit()));
        store.save(List.of(op(coveredByWithContainers("deposit"))));
        RolledSynthesis synthesis = new RolledSynthesis(store);

        assertThat(synthesis.synthesize(request(
                new QueryPredicate.Eq("event", "rolled-withdraw"))))
                .as("a:rolled-withdraw reaches the synthesized entry")
                .hasSize(1);
        assertThat(synthesis.synthesize(request(
                new QueryPredicate.Eq("event", "rolled-place"))))
                .isEmpty();
    }
}
