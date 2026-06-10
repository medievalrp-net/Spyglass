package net.medievalrp.spyglass.plugin.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
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
import net.medievalrp.spyglass.api.query.Sort;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.api.util.EventIds;
import org.bukkit.Material;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.clickhouse.ClickHouseContainer;

/**
 * The #22 parity contract on a real ClickHouse: a store holding
 * engine-style persisted receipt rows and a store holding one
 * rollback-op record behind {@link SynthesizingRecordStore} must
 * answer identical search requests with identical user-visible
 * results. Two databases, one container, same originals.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ClickHouseSynthesisParityIT {

    private static final UUID WORLD = UUID.fromString("77777777-7777-7777-7777-777777777777");
    private static final UUID GRIEFER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final Instant GRIEF_TIME = Instant.parse("2026-06-10T10:00:00Z");
    private static final Instant OP_TIME = Instant.parse("2026-06-10T11:00:00Z");
    private static final Instant EXPIRES = Instant.parse("2026-07-10T00:00:00Z");

    private ClickHouseContainer container;
    private ClickHouseRecordStore receiptsStore;
    private ClickHouseRecordStore synthBase;
    private RecordStore synthStore;

    @BeforeAll
    void setup() throws Exception {
        assumeThat(DockerClientFactory.instance().isDockerAvailable())
                .as("docker not available")
                .isTrue();
        container = new ClickHouseContainer("clickhouse/clickhouse-server:24.8-alpine");
        container.start();
        receiptsStore = new ClickHouseRecordStore(
                container.getHost(), container.getMappedPort(8123),
                "parity_receipts", "event_records",
                container.getUsername(), container.getPassword(), false);
        synthBase = new ClickHouseRecordStore(
                container.getHost(), container.getMappedPort(8123),
                "parity_synth", "event_records",
                container.getUsername(), container.getPassword(), false);
        synthStore = new SynthesizingRecordStore(synthBase, true);

        List<EventRecord> originals = List.of(
                griefBreak(100), griefBreak(101), griefBreak(102),
                bystanderPlace(500));
        receiptsStore.save(originals);
        synthBase.save(originals);

        // Receipts store: persisted rows exactly as the engine's
        // buildRollbackSourceRecord used to emit them.
        List<EventRecord> receipts = new ArrayList<>();
        for (int x : new int[]{100, 101, 102}) {
            receipts.add(new BlockUseRecord(EventIds.newId(), "rolled-place",
                    OP_TIME, EXPIRES,
                    Origin.rollback("Operator"), Source.environment("ROLLBACK"),
                    new BlockLocation(WORLD, "world", x, 64, 0), "test", "STONE"));
        }
        receiptsStore.save(receipts);

        // Synth store: one rollback-op record instead.
        String blob = UndoReferenceBson.encodeBase64(griefQuery(), "ROLLBACK", OP_TIME,
                List.of(new UndoReferenceBson.WorldBox(WORLD, 100, 64, 0, 102, 64, 0)), 3, 0);
        synthBase.save(List.of(new RollbackOpRecord(EventIds.newId(), "rollback-op",
                OP_TIME, EXPIRES,
                Origin.rollback("Operator"), Source.player(UUID.randomUUID(), "Operator"),
                new BlockLocation(WORLD, "world", 100, 64, 0), "test",
                "ROLLBACK", blob)));

        flush(receiptsStore);
        flush(synthBase);
    }

    @AfterAll
    void teardown() {
        if (receiptsStore != null) {
            receiptsStore.close();
        }
        if (synthBase != null) {
            synthBase.close();
        }
        if (container != null) {
            container.stop();
        }
    }

    private static void flush(ClickHouseRecordStore store) throws Exception {
        store.client().execute("SYSTEM FLUSH ASYNC INSERT QUEUE")
                .get(30, java.util.concurrent.TimeUnit.SECONDS).close();
    }

    private static QueryRequest griefQuery() {
        return new QueryRequest(List.of(
                new QueryPredicate.Eq("source.playerId", GRIEFER),
                new QueryPredicate.Eq("event", "break")),
                Sort.NEWEST_FIRST, 10_000, EnumSet.of(Flag.NO_GROUP), false);
    }

    private static BlockBreakRecord griefBreak(int x) {
        BlockSnapshot stone = new BlockSnapshot(Material.STONE, "minecraft:stone",
                List.of(), List.of(), List.of(), List.of(), null);
        BlockSnapshot air = new BlockSnapshot(Material.AIR, "minecraft:air",
                List.of(), List.of(), List.of(), List.of(), null);
        return new BlockBreakRecord(EventIds.newId(), "break",
                GRIEF_TIME, EXPIRES,
                Origin.player(), Source.player(GRIEFER, "Griefer"),
                new BlockLocation(WORLD, "world", x, 64, 0),
                "test", "STONE", stone, air);
    }

    private static BlockBreakRecord bystanderPlace(int x) {
        BlockSnapshot stone = new BlockSnapshot(Material.STONE, "minecraft:stone",
                List.of(), List.of(), List.of(), List.of(), null);
        return new BlockBreakRecord(EventIds.newId(), "break",
                GRIEF_TIME, EXPIRES,
                Origin.player(), Source.player(UUID.randomUUID(), "Bystander"),
                new BlockLocation(WORLD, "world", x, 64, 0),
                "test", "DIRT", stone, stone);
    }

    private static QueryRequest search(QueryPredicate... predicates) {
        return new QueryRequest(List.of(predicates), Sort.NEWEST_FIRST,
                100, EnumSet.of(Flag.NO_GROUP), false);
    }

    /** User-visible projection — ids legitimately differ. */
    private static List<String> project(List<EventRecord> records) {
        return records.stream()
                .filter(r -> r.event().startsWith("rolled"))
                .map(r -> String.join("|", r.event(), r.target(),
                        String.valueOf(r.location().x()),
                        String.valueOf(r.location().y()),
                        String.valueOf(r.location().z()),
                        r.occurred().toString(),
                        r.source().kind(), r.source().description(),
                        r.origin().kind(), r.origin().detail()))
                .sorted(Comparator.naturalOrder())
                .toList();
    }

    private void assertParity(QueryRequest request) {
        List<String> persisted = project(receiptsStore.query(request).records());
        List<String> synthesized = project(synthStore.query(request).records());
        assertThat(synthesized).isEqualTo(persisted);
        assertThat(persisted).isNotEmpty();
    }

    @Test
    void blockHistoryAtOneLocationMatches() {
        assertParity(search(
                new QueryPredicate.Eq("location.worldId", WORLD),
                new QueryPredicate.Eq("location.x", 101),
                new QueryPredicate.Eq("location.y", 64),
                new QueryPredicate.Eq("location.z", 0),
                new QueryPredicate.Range("occurred",
                        GRIEF_TIME.minusSeconds(3600), OP_TIME.plusSeconds(3600))));
    }

    @Test
    void rolledPlaceEventFilterMatches() {
        assertParity(search(
                new QueryPredicate.Eq("event", "rolled-place"),
                new QueryPredicate.Range("occurred",
                        OP_TIME.minusSeconds(60), OP_TIME.plusSeconds(60))));
    }

    @Test
    void areaSearchMatches() {
        assertParity(search(
                new QueryPredicate.Eq("location.worldId", WORLD),
                new QueryPredicate.Range("location.x", 100, 102),
                new QueryPredicate.Range("occurred",
                        GRIEF_TIME.minusSeconds(3600), OP_TIME.plusSeconds(3600))));
    }

    @Test
    void windowExcludingTheOperationShowsNothingEitherWay() {
        QueryRequest request = search(
                new QueryPredicate.Eq("event", "rolled-place"),
                new QueryPredicate.Range("occurred",
                        GRIEF_TIME.minusSeconds(60), GRIEF_TIME.plusSeconds(60)));
        assertThat(project(receiptsStore.query(request).records())).isEmpty();
        assertThat(project(synthStore.query(request).records())).isEmpty();
    }

    @Test
    void bystanderBlockShowsNoRolledEntries() {
        QueryRequest request = search(
                new QueryPredicate.Eq("location.worldId", WORLD),
                new QueryPredicate.Eq("location.x", 500),
                new QueryPredicate.Range("occurred",
                        GRIEF_TIME.minusSeconds(3600), OP_TIME.plusSeconds(3600)));
        assertThat(project(receiptsStore.query(request).records())).isEmpty();
        assertThat(project(synthStore.query(request).records())).isEmpty();
    }
}
