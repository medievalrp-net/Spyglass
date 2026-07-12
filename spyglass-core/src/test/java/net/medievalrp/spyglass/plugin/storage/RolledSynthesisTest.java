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
        int queryCount;

        @Override
        public void save(List<EventRecord> records) {
            rows.addAll(records);
        }

        @Override
        public QueryResult query(QueryRequest request) {
            queryCount++;
            // Honor the requested sort like every real backend does - the
            // per-cell net fold (#321) reads the verify stream's order.
            java.util.Comparator<EventRecord> byTime = java.util.Comparator
                    .comparing(EventRecord::occurred)
                    .thenComparing(r -> r.id().toString());
            if (request.sort() != net.medievalrp.spyglass.api.query.Sort.OLDEST_FIRST) {
                byTime = byTime.reversed();
            }
            List<EventRecord> matched = rows.stream()
                    .filter(r -> PredicateEvaluator.matchesAll(request.predicates(), r))
                    .sorted(byTime)
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

    // The default search and the wand render aggregations when grouping is
    // on; receipts that only reach records() are invisible there. The
    // decorator must fold them into the grouped side too, counted per
    // (event, target, operator).
    @Test
    void groupedQueriesSeeSynthesizedReceiptsAsAggregations() {
        MemoryStore store = new MemoryStore();
        store.save(List.of(griefBreak(1), griefBreak(2)));
        store.save(List.of(op(griefQuery(), "ROLLBACK", OP_TIME)));
        SynthesizingRecordStore wrapped = new SynthesizingRecordStore(store, true);

        QueryRequest grouped = new QueryRequest(
                List.of(new QueryPredicate.Eq("location.worldId", WORLD)),
                net.medievalrp.spyglass.api.query.Sort.NEWEST_FIRST,
                100, EnumSet.noneOf(Flag.class), true);
        QueryResult result = wrapped.query(grouped);

        assertThat(result.aggregations())
                .anyMatch(aggregation -> "rolled-place".equals(aggregation.sample().event())
                        && aggregation.count() == 2);

        // Ungrouped requests keep the store's aggregation list untouched.
        QueryRequest ungrouped = new QueryRequest(
                List.of(new QueryPredicate.Eq("location.worldId", WORLD)),
                net.medievalrp.spyglass.api.query.Sort.NEWEST_FIRST,
                100, EnumSet.noneOf(Flag.class), false);
        assertThat(wrapped.query(ungrouped).aggregations()).isEmpty();
    }

    // ---- #302: coverage honors the op's inclusion flags ----

    private static net.medievalrp.spyglass.api.event.BlockPlaceRecord chestPlace(int x) {
        return chestPlace(x, GRIEF_TIME);
    }

    private static net.medievalrp.spyglass.api.event.BlockPlaceRecord chestPlace(int x, Instant at) {
        BlockSnapshot air = new BlockSnapshot(Material.AIR, "minecraft:air",
                List.of(), List.of(), List.of(), List.of(), null);
        BlockSnapshot chest = new BlockSnapshot(Material.CHEST, "minecraft:chest",
                List.of(), List.of(), List.of(), List.of(), null);
        return new net.medievalrp.spyglass.api.event.BlockPlaceRecord(UUID.randomUUID(), "place",
                at, at.plusSeconds(86400),
                Origin.player(), Source.player(GRIEFER, "Griefer"),
                new BlockLocation(WORLD, "world", x, 64, 0),
                "test", "CHEST", air, chest);
    }

    private static net.medievalrp.spyglass.api.event.ContainerDepositRecord deposit(int x) {
        return deposit(x, GRIEF_TIME);
    }

    private static net.medievalrp.spyglass.api.event.ContainerDepositRecord deposit(int x, Instant at) {
        return new net.medievalrp.spyglass.api.event.ContainerDepositRecord(UUID.randomUUID(),
                "deposit", at, at.plusSeconds(86400),
                Origin.player(), Source.player(GRIEFER, "Griefer"),
                new BlockLocation(WORLD, "world", x, 64, 0), "test",
                "DIRT", "CHEST", 0, 8, null,
                new net.medievalrp.spyglass.api.event.StoredItem(
                        0, "DIRT", "", null, List.of(), List.of(), null));
    }

    private static QueryRequest griefQueryWithFlags(Flag... flags) {
        EnumSet<Flag> set = EnumSet.noneOf(Flag.class);
        set.addAll(List.of(flags));
        return new QueryRequest(
                List.of(new QueryPredicate.Eq("source.playerId", GRIEFER)),
                net.medievalrp.spyglass.api.query.Sort.NEWEST_FIRST, 100, set, false);
    }

    @Test
    void opWithoutContainersFlagGetsNoContainerBlockReceipt() {
        MemoryStore store = new MemoryStore();
        store.save(List.of(chestPlace(1)));
        store.save(List.of(op(griefQueryWithFlags(), "ROLLBACK", OP_TIME)));

        // The engine's gate skipped this chest cell, so the audit must
        // not claim the op broke it (#302).
        assertThat(new RolledSynthesis(store, java.util.Set.of("CHEST"))
                .synthesize(request(new QueryPredicate.Eq("location.worldId", WORLD))))
                .isEmpty();
    }

    @Test
    void opWithContainersFlagKeepsTheReceipt() {
        MemoryStore store = new MemoryStore();
        store.save(List.of(chestPlace(1)));
        store.save(List.of(op(griefQueryWithFlags(Flag.INCLUDE_CONTAINERS), "ROLLBACK", OP_TIME)));

        List<EventRecord> rolled = new RolledSynthesis(store, java.util.Set.of("CHEST"))
                .synthesize(request(new QueryPredicate.Eq("location.worldId", WORLD)));
        assertThat(rolled).hasSize(1);
        assertThat(rolled.get(0).event()).isEqualTo("rolled-break");
        assertThat(rolled.get(0).target()).isEqualTo("CHEST");
    }

    @Test
    void opWithoutContainersFlagGetsNoTransactionReceipt() {
        MemoryStore store = new MemoryStore();
        store.save(List.of(deposit(1)));
        store.save(List.of(op(griefQueryWithFlags(), "ROLLBACK", OP_TIME)));

        // ContainerSlotWrite effects are container-gated by shape alone -
        // no material set needed.
        assertThat(new RolledSynthesis(store)
                .synthesize(request(new QueryPredicate.Eq("location.worldId", WORLD))))
                .isEmpty();
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

        // Restoring a break re-applies it: the block becomes air. The
        // entry names the STONE that was re-broken, not the air that
        // replaced it - "ROLLBACK broke AIR" identified nothing (#269).
        assertThat(rolled).hasSize(1);
        assertThat(rolled.get(0).event()).isEqualTo("rolled-break");
        assertThat(rolled.get(0).target()).isEqualTo("STONE");
    }

    // ---- #321: multi-event cells net to one receipt ----

    private static net.medievalrp.spyglass.api.event.BlockPlaceRecord dirtPlace(int x, Instant at) {
        BlockSnapshot air = new BlockSnapshot(Material.AIR, "minecraft:air",
                List.of(), List.of(), List.of(), List.of(), null);
        BlockSnapshot dirt = new BlockSnapshot(Material.DIRT, "minecraft:dirt",
                List.of(), List.of(), List.of(), List.of(), null);
        return new net.medievalrp.spyglass.api.event.BlockPlaceRecord(UUID.randomUUID(), "place",
                at, at.plusSeconds(86400),
                Origin.player(), Source.player(GRIEFER, "Griefer"),
                new BlockLocation(WORLD, "world", x, 64, 0),
                "test", "DIRT", air, dirt);
    }

    private static BlockBreakRecord dirtBreak(int x, Instant at) {
        BlockSnapshot dirt = new BlockSnapshot(Material.DIRT, "minecraft:dirt",
                List.of(), List.of(), List.of(), List.of(), null);
        BlockSnapshot air = new BlockSnapshot(Material.AIR, "minecraft:air",
                List.of(), List.of(), List.of(), List.of(), null);
        return new BlockBreakRecord(UUID.randomUUID(), "break",
                at, at.plusSeconds(86400),
                Origin.player(), Source.player(GRIEFER, "Griefer"),
                new BlockLocation(WORLD, "world", x, 64, 0),
                "test", "DIRT", dirt, air);
    }

    private static QueryRequest grieferQuery() {
        return request(new QueryPredicate.Eq("source.playerId", GRIEFER));
    }

    @Test
    void multiEventCellNetsToOneReceipt() {
        MemoryStore store = new MemoryStore();
        // The #321 repro: place, break, place again at ONE cell. The net
        // world change of rolling all three back is dirt -> air.
        store.save(List.of(
                dirtPlace(1, GRIEF_TIME),
                dirtBreak(1, GRIEF_TIME.plusSeconds(1)),
                dirtPlace(1, GRIEF_TIME.plusSeconds(2))));
        store.save(List.of(op(grieferQuery(), "ROLLBACK", OP_TIME)));

        List<EventRecord> rolled = new RolledSynthesis(store)
                .synthesize(request(new QueryPredicate.Eq("location.worldId", WORLD)));

        // One net receipt, not broke x2 + placed x1.
        assertThat(rolled).hasSize(1);
        assertThat(rolled.get(0).event()).isEqualTo("rolled-break");
        assertThat(rolled.get(0).target()).isEqualTo("DIRT");
    }

    @Test
    void cancellingPairSynthesizesNothing() {
        MemoryStore store = new MemoryStore();
        // Place then break: rolling both back returns the cell to the
        // air it started as. No net transition, no receipt - a "broke
        // AIR" line identifies nothing (#269).
        store.save(List.of(
                dirtPlace(1, GRIEF_TIME),
                dirtBreak(1, GRIEF_TIME.plusSeconds(1))));
        store.save(List.of(op(grieferQuery(), "ROLLBACK", OP_TIME)));

        assertThat(new RolledSynthesis(store)
                .synthesize(request(new QueryPredicate.Eq("location.worldId", WORLD))))
                .isEmpty();
    }

    @Test
    void restoreOfMultiEventCellNetsToOnePlace() {
        MemoryStore store = new MemoryStore();
        store.save(List.of(
                dirtPlace(1, GRIEF_TIME),
                dirtBreak(1, GRIEF_TIME.plusSeconds(1)),
                dirtPlace(1, GRIEF_TIME.plusSeconds(2))));
        store.save(List.of(op(grieferQuery(), "RESTORE", OP_TIME)));

        List<EventRecord> rolled = new RolledSynthesis(store)
                .synthesize(request(new QueryPredicate.Eq("location.worldId", WORLD)));

        // Re-applying the history oldest-first lands on the newest
        // event's outcome: dirt. Net = air -> dirt, one rolled-place.
        assertThat(rolled).hasSize(1);
        assertThat(rolled.get(0).event()).isEqualTo("rolled-place");
        assertThat(rolled.get(0).target()).isEqualTo("DIRT");
    }

    @Test
    void churnCellAndPlainCellEachKeepTheirOwnReceipt() {
        MemoryStore store = new MemoryStore();
        store.save(List.of(
                dirtPlace(1, GRIEF_TIME),
                dirtBreak(1, GRIEF_TIME.plusSeconds(1)),
                dirtPlace(1, GRIEF_TIME.plusSeconds(2)),
                griefBreak(2)));
        store.save(List.of(op(grieferQuery(), "ROLLBACK", OP_TIME)));

        List<EventRecord> rolled = new RolledSynthesis(store)
                .synthesize(request(new QueryPredicate.Eq("location.worldId", WORLD)));

        // The churn cell nets to one rolled-break DIRT; the untouched
        // single-event cell keeps its ordinary rolled-place STONE.
        assertThat(rolled).hasSize(2);
        assertThat(rolled).anyMatch(r -> "rolled-break".equals(r.event())
                && "DIRT".equals(r.target()) && r.location().x() == 1);
        assertThat(rolled).anyMatch(r -> "rolled-place".equals(r.event())
                && "STONE".equals(r.target()) && r.location().x() == 2);
    }

    @Test
    void mixedCellWithoutContainersFlagNetsOnlyPlainOriginals() {
        MemoryStore store = new MemoryStore();
        // Dirt churn plus a chest place at ONE cell, op stored WITHOUT
        // --containers: the chest-covering original is gated out (#302)
        // BEFORE the fold, so the net spans only the dirt events.
        store.save(List.of(
                dirtPlace(1, GRIEF_TIME),
                dirtBreak(1, GRIEF_TIME.plusSeconds(1)),
                dirtPlace(1, GRIEF_TIME.plusSeconds(2)),
                chestPlace(1, GRIEF_TIME.plusSeconds(3))));
        store.save(List.of(op(griefQueryWithFlags(), "ROLLBACK", OP_TIME)));

        List<EventRecord> rolled = new RolledSynthesis(store, java.util.Set.of("CHEST"))
                .synthesize(request(new QueryPredicate.Eq("location.worldId", WORLD)));

        assertThat(rolled).hasSize(1);
        assertThat(rolled.get(0).event()).isEqualTo("rolled-break");
        assertThat(rolled.get(0).target()).isEqualTo("DIRT");
    }

    @Test
    void mixedCellWithContainersFlagNetsAcrossTheChestToo() {
        MemoryStore store = new MemoryStore();
        // Same churn but the op ran WITH --containers: the chest place is
        // covered, so the net spans all four events - from the newest
        // covered state (the chest) to the oldest restored one (air).
        store.save(List.of(
                dirtPlace(1, GRIEF_TIME),
                dirtBreak(1, GRIEF_TIME.plusSeconds(1)),
                dirtPlace(1, GRIEF_TIME.plusSeconds(2)),
                chestPlace(1, GRIEF_TIME.plusSeconds(3))));
        store.save(List.of(op(griefQueryWithFlags(Flag.INCLUDE_CONTAINERS), "ROLLBACK", OP_TIME)));

        List<EventRecord> rolled = new RolledSynthesis(store, java.util.Set.of("CHEST"))
                .synthesize(request(new QueryPredicate.Eq("location.worldId", WORLD)));

        assertThat(rolled).hasSize(1);
        assertThat(rolled.get(0).event()).isEqualTo("rolled-break");
        assertThat(rolled.get(0).target()).isEqualTo("CHEST");
    }

    @Test
    void netReceiptIdsAreDeterministicAcrossSearches() {
        MemoryStore store = new MemoryStore();
        store.save(List.of(
                dirtPlace(1, GRIEF_TIME),
                dirtBreak(1, GRIEF_TIME.plusSeconds(1)),
                dirtPlace(1, GRIEF_TIME.plusSeconds(2))));
        store.save(List.of(op(grieferQuery(), "ROLLBACK", OP_TIME)));
        RolledSynthesis synthesis = new RolledSynthesis(store);
        QueryRequest search = request(new QueryPredicate.Eq("location.worldId", WORLD));

        List<EventRecord> first = synthesis.synthesize(search);
        List<EventRecord> second = synthesis.synthesize(search);

        assertThat(first).hasSize(1);
        assertThat(second).hasSize(1);
        assertThat(first.get(0).id()).isEqualTo(second.get(0).id());
    }

    @Test
    void twoOpsAtTheSameCellSynthesizeDistinctReceipts() {
        MemoryStore store = new MemoryStore();
        // One covered cell, two separate rollback ops over it. The net-cell
        // id keys on the OP, so the fold is per (op, cell) and never across
        // ops - each op stamps its own receipt with its own id.
        store.save(List.of(griefBreak(1)));
        store.save(List.of(
                op(griefQuery(), "ROLLBACK", OP_TIME),
                op(griefQuery(), "ROLLBACK", OP_TIME)));

        List<EventRecord> rolled = new RolledSynthesis(store)
                .synthesize(request(new QueryPredicate.Eq("location.worldId", WORLD)));

        assertThat(rolled).hasSize(2);
        assertThat(rolled).extracting(EventRecord::id).doesNotHaveDuplicates();
    }

    @Test
    void orientationChangeIsNotSuppressed() {
        MemoryStore store = new MemoryStore();
        BlockSnapshot air = new BlockSnapshot(Material.AIR, "minecraft:air",
                List.of(), List.of(), List.of(), List.of(), null);
        BlockSnapshot stairsNorth = new BlockSnapshot(Material.OAK_STAIRS,
                "minecraft:oak_stairs[facing=north]",
                List.of(), List.of(), List.of(), List.of(), null);
        BlockSnapshot stairsSouth = new BlockSnapshot(Material.OAK_STAIRS,
                "minecraft:oak_stairs[facing=south]",
                List.of(), List.of(), List.of(), List.of(), null);
        // Break the north-facing stairs, then place south-facing ones in the
        // same cell. The net rollback transition is south -> north: same
        // material but different block data, so it is not an identity net
        // and must keep its receipt.
        store.save(List.of(
                new BlockBreakRecord(UUID.randomUUID(), "break",
                        GRIEF_TIME, GRIEF_TIME.plusSeconds(86400),
                        Origin.player(), Source.player(GRIEFER, "Griefer"),
                        new BlockLocation(WORLD, "world", 1, 64, 0),
                        "test", "OAK_STAIRS", stairsNorth, air),
                new net.medievalrp.spyglass.api.event.BlockPlaceRecord(UUID.randomUUID(), "place",
                        GRIEF_TIME.plusSeconds(1), GRIEF_TIME.plusSeconds(86400),
                        Origin.player(), Source.player(GRIEFER, "Griefer"),
                        new BlockLocation(WORLD, "world", 1, 64, 0),
                        "test", "OAK_STAIRS", air, stairsSouth)));
        store.save(List.of(op(grieferQuery(), "ROLLBACK", OP_TIME)));

        List<EventRecord> rolled = new RolledSynthesis(store)
                .synthesize(request(new QueryPredicate.Eq("location.worldId", WORLD)));

        assertThat(rolled).hasSize(1);
        assertThat(rolled.get(0).target()).isEqualTo("OAK_STAIRS");
    }

    @Test
    void breakThenReplaceSameMaterialSuppresses() {
        MemoryStore store = new MemoryStore();
        // Break DIRT then place DIRT back, same block data. The net rollback
        // is dirt -> dirt, an identity, so nothing is synthesized.
        store.save(List.of(
                dirtBreak(1, GRIEF_TIME),
                dirtPlace(1, GRIEF_TIME.plusSeconds(1))));
        store.save(List.of(op(grieferQuery(), "ROLLBACK", OP_TIME)));

        assertThat(new RolledSynthesis(store)
                .synthesize(request(new QueryPredicate.Eq("location.worldId", WORLD))))
                .isEmpty();
    }

    @Test
    void groupedChurnCellCollapsesToCountOne() {
        MemoryStore store = new MemoryStore();
        // The P/B/P churn at ONE cell. A grouped search must show a single
        // rolled-break aggregation counted ONCE, not the "x2" a per-event
        // receipt would have produced (#321) - the disappearing count proof.
        store.save(List.of(
                dirtPlace(1, GRIEF_TIME),
                dirtBreak(1, GRIEF_TIME.plusSeconds(1)),
                dirtPlace(1, GRIEF_TIME.plusSeconds(2))));
        store.save(List.of(op(grieferQuery(), "ROLLBACK", OP_TIME)));
        SynthesizingRecordStore wrapped = new SynthesizingRecordStore(store, true);

        QueryRequest grouped = new QueryRequest(
                List.of(new QueryPredicate.Eq("location.worldId", WORLD)),
                net.medievalrp.spyglass.api.query.Sort.NEWEST_FIRST,
                100, EnumSet.noneOf(Flag.class), true);
        QueryResult result = wrapped.query(grouped);

        assertThat(result.aggregations())
                .anyMatch(aggregation -> "rolled-break".equals(aggregation.sample().event())
                        && aggregation.count() == 1);
    }

    @Test
    void containerReceiptsStayPerOriginal() {
        MemoryStore store = new MemoryStore();
        // Two deposits into the SAME container cell. Container-slot receipts
        // are NOT folded (#321 coalesces block writes only), so each covered
        // transaction keeps its own rolled-withdraw line with its own id.
        store.save(List.of(
                deposit(1, GRIEF_TIME),
                deposit(1, GRIEF_TIME.plusSeconds(1))));
        store.save(List.of(op(griefQueryWithFlags(Flag.INCLUDE_CONTAINERS), "ROLLBACK", OP_TIME)));

        List<EventRecord> rolled = new RolledSynthesis(store)
                .synthesize(request(new QueryPredicate.Eq("location.worldId", WORLD)));

        assertThat(rolled).hasSize(2);
        assertThat(rolled).allMatch(r -> "rolled-withdraw".equals(r.event()));
        assertThat(rolled).extracting(EventRecord::id).doesNotHaveDuplicates();
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
    @Test
    void positivePlayerFilterSkipsSynthesisWithoutAnyStoreQuery() {
        MemoryStore store = new MemoryStore();
        store.save(List.of(griefBreak(1),
                op(coveredQuery(), "ROLLBACK", OP_TIME)));
        RolledSynthesis synthesis = new RolledSynthesis(store);

        QueryRequest request = new QueryRequest(List.of(
                new QueryPredicate.Eq("source.playerId", GRIEFER)),
                net.medievalrp.spyglass.api.query.Sort.NEWEST_FIRST, 100,
                EnumSet.of(Flag.NO_GROUP), false);
        List<EventRecord> out = synthesis.synthesize(request);

        assertThat(out).isEmpty();
        assertThat(store.queryCount)
                .as("a p:-filtered request must not pay the op scan (#33)")
                .isZero();
    }

    @Test
    void negatedPlayerFilterStaysFeasible() {
        MemoryStore store = new MemoryStore();
        store.save(List.of(griefBreak(1),
                op(coveredQuery(), "ROLLBACK", OP_TIME)));
        RolledSynthesis synthesis = new RolledSynthesis(store);

        QueryRequest request = new QueryRequest(List.of(
                new QueryPredicate.Not(new QueryPredicate.Eq("source.playerId", GRIEFER))),
                net.medievalrp.spyglass.api.query.Sort.NEWEST_FIRST, 100,
                EnumSet.of(Flag.NO_GROUP), false);
        List<EventRecord> out = synthesis.synthesize(request);

        assertThat(out)
                .as("Not(p:) matches the environment source; synthesis must run")
                .isNotEmpty();
    }

    @Test
    void opOutsideTheSearchedBoxIsNotExpanded() {
        MemoryStore store = new MemoryStore();
        store.save(List.of(griefBreak(1),
                op(coveredQuery(), "ROLLBACK", OP_TIME)));   // box spans x 0..10
        RolledSynthesis synthesis = new RolledSynthesis(store);

        QueryRequest farAway = new QueryRequest(List.of(
                new QueryPredicate.Range("location.x", 5000, 5016),
                new QueryPredicate.Range("location.z", -16, 16)),
                net.medievalrp.spyglass.api.query.Sort.NEWEST_FIRST, 100,
                EnumSet.of(Flag.NO_GROUP), false);
        List<EventRecord> out = synthesis.synthesize(farAway);

        assertThat(out).isEmpty();
        assertThat(store.queryCount)
                .as("only the op scan runs; the op expansion is pruned by its box")
                .isEqualTo(1);
    }

    @Test
    void expansionStopsOnceTheLimitIsFilled() {
        MemoryStore store = new MemoryStore();
        store.save(List.of(griefBreak(1),
                op(coveredQuery(), "ROLLBACK", OP_TIME),
                op(coveredQuery(), "ROLLBACK", OP_TIME)));
        RolledSynthesis synthesis = new RolledSynthesis(store);

        QueryRequest limited = new QueryRequest(List.of(),
                net.medievalrp.spyglass.api.query.Sort.NEWEST_FIRST, 1,
                EnumSet.of(Flag.NO_GROUP), false);
        List<EventRecord> out = synthesis.synthesize(limited);

        assertThat(out).hasSize(1);
        assertThat(store.queryCount)
                .as("op scan + exactly one expansion for limit=1")
                .isEqualTo(2);
    }

    private static QueryRequest coveredQuery() {
        return new QueryRequest(List.of(
                new QueryPredicate.Eq("source.playerId", GRIEFER)),
                net.medievalrp.spyglass.api.query.Sort.NEWEST_FIRST, 10000,
                EnumSet.of(Flag.NO_GROUP), false);
    }

    // ---- #319: rolledBackAmong marks the ORIGINAL records a rollback reverted ----

    private static QueryRequest playerLookup() {
        return new QueryRequest(List.of(new QueryPredicate.Eq("source.playerId", GRIEFER)),
                net.medievalrp.spyglass.api.query.Sort.NEWEST_FIRST, 100,
                EnumSet.of(Flag.NO_GROUP), false);
    }

    @Test
    void rolledBackAmongMarksACoveredRecord() {
        MemoryStore store = new MemoryStore();
        store.save(List.of(op(griefQuery(), "ROLLBACK", OP_TIME)));
        BlockBreakRecord grief = griefBreak(1);

        assertThat(new RolledSynthesis(store)
                .rolledBackAmong(request(new QueryPredicate.Eq("location.worldId", WORLD)), List.of(grief)))
                .containsExactly(grief.id());
    }

    // The headline case: a p:<griefer> lookup returns no synthesized receipt
    // (they carry the environment source), yet the griefer's reverted rows
    // must still be marked. rolledBackAmong ignores that receipt gate.
    @Test
    void rolledBackAmongMarksRevertedRowsForAPlayerLookup() {
        MemoryStore store = new MemoryStore();
        store.save(List.of(op(griefQuery(), "ROLLBACK", OP_TIME)));
        BlockBreakRecord grief = griefBreak(1);
        RolledSynthesis synthesis = new RolledSynthesis(store);

        assertThat(synthesis.synthesize(playerLookup()))
                .as("p:-filtered synthesis is empty (receipts are environment-sourced)")
                .isEmpty();
        assertThat(synthesis.rolledBackAmong(playerLookup(), List.of(grief)))
                .as("but the griefer's reverted row is still marked")
                .containsExactly(grief.id());
    }

    @Test
    void rolledBackAmongRespectsTheCeiling() {
        MemoryStore store = new MemoryStore();
        // Ceiling BEFORE the grief: the op ran before this row existed.
        store.save(List.of(op(griefQuery(), "ROLLBACK", GRIEF_TIME.minusSeconds(60))));

        assertThat(new RolledSynthesis(store)
                .rolledBackAmong(playerLookup(), List.of(griefBreak(1))))
                .isEmpty();
    }

    @Test
    void rolledBackAmongIgnoresRecordsOutsideTheOpQuery() {
        MemoryStore store = new MemoryStore();
        // Op covered breaks by the griefer; a place is not in its query.
        store.save(List.of(op(griefQuery(), "ROLLBACK", OP_TIME)));

        assertThat(new RolledSynthesis(store)
                .rolledBackAmong(playerLookup(), List.of(chestPlace(1))))
                .isEmpty();
    }

    @Test
    void rolledBackAmongHonorsTheContainersFlag() {
        MemoryStore store = new MemoryStore();
        store.save(List.of(op(griefQueryWithFlags(), "ROLLBACK", OP_TIME)));
        // Without --containers the engine skipped the chest cell (#302),
        // so the row must not be marked reverted.
        assertThat(new RolledSynthesis(store, java.util.Set.of("CHEST"))
                .rolledBackAmong(playerLookup(), List.of(chestPlace(1))))
                .isEmpty();

        MemoryStore withFlag = new MemoryStore();
        withFlag.save(List.of(op(griefQueryWithFlags(Flag.INCLUDE_CONTAINERS), "ROLLBACK", OP_TIME)));
        var chest = chestPlace(1);
        assertThat(new RolledSynthesis(withFlag, java.util.Set.of("CHEST"))
                .rolledBackAmong(playerLookup(), List.of(chest)))
                .containsExactly(chest.id());
    }

    @Test
    void rolledBackAmongIsEmptyWithNoOpsOrNoCandidates() {
        MemoryStore empty = new MemoryStore();
        assertThat(new RolledSynthesis(empty)
                .rolledBackAmong(playerLookup(), List.of(griefBreak(1))))
                .as("no ops in the store")
                .isEmpty();

        MemoryStore withOp = new MemoryStore();
        withOp.save(List.of(op(griefQuery(), "ROLLBACK", OP_TIME)));
        assertThat(new RolledSynthesis(withOp)
                .rolledBackAmong(playerLookup(), List.of()))
                .as("no candidates to mark")
                .isEmpty();
    }

}
