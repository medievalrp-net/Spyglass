package net.medievalrp.spyglass.plugin.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import net.medievalrp.spyglass.api.event.BlockBreakRecord;
import net.medievalrp.spyglass.api.event.BlockPlaceRecord;
import net.medievalrp.spyglass.api.event.BlockSnapshot;
import net.medievalrp.spyglass.api.event.ChatRecord;
import net.medievalrp.spyglass.api.event.ContainerDepositRecord;
import net.medievalrp.spyglass.api.event.EntityDeathRecord;
import net.medievalrp.spyglass.api.event.EventRecord;
import net.medievalrp.spyglass.api.event.Origin;
import net.medievalrp.spyglass.api.event.Source;
import net.medievalrp.spyglass.api.event.StoredItem;
import net.medievalrp.spyglass.api.query.Flag;
import net.medievalrp.spyglass.api.query.QueryPredicate;
import net.medievalrp.spyglass.api.query.QueryRequest;
import net.medievalrp.spyglass.api.query.QueryResult;
import net.medievalrp.spyglass.api.query.Sort;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.plugin.config.SpyglassConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.clickhouse.ClickHouseContainer;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ClickHouseRecordStoreIT {

    private static final UUID ALICE = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID WORLD = UUID.fromString("77777777-7777-7777-7777-777777777777");

    private ClickHouseContainer container;
    private ClickHouseRecordStore store;

    @BeforeAll
    void setup() {
        assumeThat(DockerClientFactory.instance().isDockerAvailable())
                .as("docker not available")
                .isTrue();
        container = new ClickHouseContainer("clickhouse/clickhouse-server:24.8-alpine");
        container.start();

        SpyglassConfig.ClickHouse config = new SpyglassConfig.ClickHouse(
                container.getHost(),
                container.getMappedPort(8123),
                "sg_it",
                "event_records_it",
                container.getUsername(),
                container.getPassword(),
                false);
        store = new ClickHouseRecordStore(config);
    }

    @AfterAll
    void teardown() {
        if (store != null) {
            store.close();
        }
        if (container != null) {
            container.stop();
        }
    }

    @BeforeEach
    void wipe() throws Exception {
        // CH MergeTree TTL fires opportunistically on background
        // merges. Wiping between tests removes the cross-test data
        // dependency.
        store.client().execute("TRUNCATE TABLE `sg_it`.`event_records_it`")
                .get(30, java.util.concurrent.TimeUnit.SECONDS).close();
    }

    @Test
    void savesAndQueriesAllRecordTypes() {
        Instant now = Instant.now().minusSeconds(60);
        BlockLocation location = new BlockLocation(WORLD, "world", 10, 64, 20);
        Origin origin = Origin.player();
        Source source = Source.player(ALICE, "Alice");
        BlockSnapshot stone = new BlockSnapshot(
                org.bukkit.Material.STONE, "minecraft:stone",
                List.of(), List.of(), List.of(), List.of(), null);
        BlockSnapshot air = new BlockSnapshot(
                org.bukkit.Material.AIR, "minecraft:air",
                List.of(), List.of(), List.of(), List.of(), null);
        StoredItem item = new StoredItem(3, "DIAMOND", null);

        List<EventRecord> records = List.of(
                new BlockBreakRecord(UUID.randomUUID(), "break", now, now.plusSeconds(3600),
                        origin, source, location, "STONE", stone, air),
                new BlockPlaceRecord(UUID.randomUUID(), "place", now, now.plusSeconds(3600),
                        origin, source, location, "STONE", air, stone),
                new ChatRecord(UUID.randomUUID(), "say", now, now.plusSeconds(3600),
                        origin, source, location, "Alice", "hello", List.of()),
                new ContainerDepositRecord(UUID.randomUUID(), "deposit", now, now.plusSeconds(3600),
                        origin, source, location, "DIAMOND", "CHEST", 3, 1, null, item),
                new EntityDeathRecord(UUID.randomUUID(), "death", now, now.plusSeconds(3600),
                        origin, source, location, "ZOMBIE", "ZOMBIE",
                        UUID.randomUUID(), "player", "ENTITY_ATTACK", null));

        store.save(records);

        QueryRequest allEvents = new QueryRequest(
                List.of(new QueryPredicate.Eq("source.playerId", ALICE)),
                Sort.NEWEST_FIRST, 50, EnumSet.noneOf(Flag.class), false);
        QueryResult result = store.query(allEvents);
        assertThat(result.records()).hasSize(5);

        QueryRequest breakOnly = new QueryRequest(
                List.of(new QueryPredicate.Eq("event", "break")),
                Sort.NEWEST_FIRST, 50, EnumSet.noneOf(Flag.class), false);
        assertThat(store.query(breakOnly).records()).hasSize(1);
    }

    @Test
    void roundTripsBlockSnapshotThroughBsonBlob() {
        Instant now = Instant.now().minusSeconds(60);
        BlockLocation loc = new BlockLocation(WORLD, "world", 5, 64, 5);
        Origin origin = Origin.player();
        Source source = Source.player(ALICE, "Alice");

        StoredItem inside = new StoredItem(0, "DIAMOND", null,
                "Excaliblur",
                List.of("Forged in primordial fire"),
                List.of("sharpness=5"));
        BlockSnapshot stoneWithItem = new BlockSnapshot(
                org.bukkit.Material.CHEST, "minecraft:chest",
                List.of(inside), List.of(), List.of(), List.of(), null);
        BlockSnapshot air = new BlockSnapshot(
                org.bukkit.Material.AIR, "minecraft:air",
                List.of(), List.of(), List.of(), List.of(), null);

        BlockBreakRecord saved = new BlockBreakRecord(
                UUID.randomUUID(), "break", now, now.plusSeconds(3600),
                origin, source, loc, "CHEST", stoneWithItem, air);
        store.save(List.of(saved));

        QueryRequest byEvent = new QueryRequest(
                List.of(new QueryPredicate.Eq("event", "break")),
                Sort.NEWEST_FIRST, 10, EnumSet.noneOf(Flag.class), false);
        BlockBreakRecord loaded = (BlockBreakRecord) store.query(byEvent).records().get(0);

        assertThat(loaded.originalBlock().material()).isEqualTo(org.bukkit.Material.CHEST);
        assertThat(loaded.originalBlock().containerItems()).hasSize(1);
        assertThat(loaded.originalBlock().containerItems().get(0).name()).isEqualTo("Excaliblur");
        assertThat(loaded.originalBlock().containerItems().get(0).enchants()).contains("sharpness=5");
    }

    @Test
    void respectsLimit() {
        Instant base = Instant.now().minusSeconds(60);
        BlockLocation loc = new BlockLocation(WORLD, "world", 0, 64, 0);
        BlockSnapshot air = new BlockSnapshot(
                org.bukkit.Material.AIR, "minecraft:air",
                List.of(), List.of(), List.of(), List.of(), null);
        BlockSnapshot stone = new BlockSnapshot(
                org.bukkit.Material.STONE, "minecraft:stone",
                List.of(), List.of(), List.of(), List.of(), null);
        Origin origin = Origin.player();
        Source source = Source.player(ALICE, "Alice");
        List<EventRecord> many = new java.util.ArrayList<>();
        for (int i = 0; i < 25; i++) {
            many.add(new BlockBreakRecord(UUID.randomUUID(), "break",
                    base.plusSeconds(i), base.plusSeconds(3600),
                    origin, source, loc, "STONE", stone, air));
        }
        store.save(many);
        QueryRequest limited = new QueryRequest(
                List.of(new QueryPredicate.Eq("event", "break")),
                Sort.NEWEST_FIRST, 5, EnumSet.noneOf(Flag.class), false);
        assertThat(store.query(limited).records()).hasSize(5);
    }

    @Test
    void rangePredicatesHitTheCorrectColumn() {
        Instant now = Instant.now().minusSeconds(60);
        BlockSnapshot air = new BlockSnapshot(
                org.bukkit.Material.AIR, "minecraft:air",
                List.of(), List.of(), List.of(), List.of(), null);
        BlockSnapshot stone = new BlockSnapshot(
                org.bukkit.Material.STONE, "minecraft:stone",
                List.of(), List.of(), List.of(), List.of(), null);
        Origin origin = Origin.player();
        Source source = Source.player(ALICE, "Alice");

        for (int i = 0; i < 5; i++) {
            store.save(List.of(new BlockBreakRecord(
                    UUID.randomUUID(), "break",
                    now.plusSeconds(i * 60L),
                    now.plusSeconds(3600),
                    origin, source,
                    new BlockLocation(WORLD, "world", 100 + i, 64, 200 + i),
                    "STONE", stone, air)));
        }

        QueryRequest spatial = new QueryRequest(
                List.of(
                        new QueryPredicate.Eq("event", "break"),
                        new QueryPredicate.Range("location.x", 101, 103),
                        new QueryPredicate.Eq("location.worldId", WORLD)),
                Sort.NEWEST_FIRST, 100, EnumSet.noneOf(Flag.class), false);
        assertThat(store.query(spatial).records()).hasSize(3);
    }

    @Test
    void summaryQueryDropsHeavySnapshotFields() {
        Instant now = Instant.now().minusSeconds(60);
        BlockLocation loc = new BlockLocation(WORLD, "world", 0, 64, 0);
        Origin origin = Origin.player();
        Source source = Source.player(ALICE, "Alice");

        BlockSnapshot bigSnapshot = new BlockSnapshot(
                org.bukkit.Material.CHEST, "minecraft:chest",
                List.of(new StoredItem(0, "DIAMOND", null)),
                List.of(), List.of(), List.of(), null);

        store.save(List.of(new BlockBreakRecord(
                UUID.randomUUID(), "break", now, now.plusSeconds(3600),
                origin, source, loc, "CHEST", bigSnapshot, null)));

        QueryRequest q = new QueryRequest(
                List.of(new QueryPredicate.Eq("event", "break")),
                Sort.NEWEST_FIRST, 10, EnumSet.noneOf(Flag.class), false);
        BlockBreakRecord summary = (BlockBreakRecord) store.querySummary(q).records().get(0);
        // The summary path skips the BSON-blob columns entirely, so
        // originalBlock / newBlock decode to null on the result side.
        // Common scalar fields (target, source, location) are still
        // present.
        assertThat(summary.originalBlock()).isNull();
        assertThat(summary.newBlock()).isNull();
        assertThat(summary.target()).isEqualTo("CHEST");
        assertThat(summary.source().playerName()).isEqualTo("Alice");
    }

    @Test
    void replayedRecordCollapsesUnderReplacingMergeTree() throws Exception {
        // Simulates a WAL replay after a mid-batch crash: the same
        // record (same UUID id, same event, same occurred) is saved
        // twice. ReplacingMergeTree on (event, occurred, id) should
        // collapse the duplicate after a merge.
        Instant now = Instant.now().minusSeconds(60);
        BlockLocation loc = new BlockLocation(WORLD, "world", 7, 64, 7);
        BlockSnapshot stone = new BlockSnapshot(
                org.bukkit.Material.STONE, "minecraft:stone",
                List.of(), List.of(), List.of(), List.of(), null);
        BlockSnapshot air = new BlockSnapshot(
                org.bukkit.Material.AIR, "minecraft:air",
                List.of(), List.of(), List.of(), List.of(), null);
        UUID recordId = UUID.randomUUID();
        BlockBreakRecord record = new BlockBreakRecord(
                recordId, "break", now, now.plusSeconds(3600),
                Origin.player(), Source.player(ALICE, "Alice"),
                loc, "STONE", stone, air);

        store.save(List.of(record));
        store.save(List.of(record));

        // Force a merge so dedup is observable; in production this
        // happens lazily on background merges.
        store.client().execute(
                "OPTIMIZE TABLE `sg_it`.`event_records_it` FINAL DEDUPLICATE")
                .get(60, java.util.concurrent.TimeUnit.SECONDS).close();

        QueryRequest byId = new QueryRequest(
                List.of(new QueryPredicate.Eq("id", recordId)),
                Sort.NEWEST_FIRST, 10, EnumSet.noneOf(Flag.class), false);
        assertThat(store.query(byId).records())
                .as("ReplacingMergeTree must collapse duplicate ids on merge")
                .hasSize(1);
    }
}
