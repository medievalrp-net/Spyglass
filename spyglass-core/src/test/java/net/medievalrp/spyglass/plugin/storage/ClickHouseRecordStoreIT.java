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

        store = new ClickHouseRecordStore(
                container.getHost(),
                container.getMappedPort(8123),
                "spyglass_it",
                "event_records_it",
                container.getUsername(),
                container.getPassword(),
                false);
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
        store.client().execute("TRUNCATE TABLE `spyglass_it`.`event_records_it`")
                .get(30, java.util.concurrent.TimeUnit.SECONDS).close();
    }

    // save() is fire-and-forget (async_insert=1, wait_for_async_insert=0):
    // the INSERT acks before the server materializes the part, so an
    // immediate SELECT races the server-side flush and reads empty.
    // Tests need read-your-writes; force the flush between save and query.
    private void flushAsyncInserts() {
        try {
            store.client().execute("SYSTEM FLUSH ASYNC INSERT QUEUE")
                    .get(30, java.util.concurrent.TimeUnit.SECONDS).close();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("async-insert flush interrupted", ie);
        } catch (Exception ex) {
            throw new IllegalStateException("async-insert flush failed", ex);
        }
    }

    @Test
    void perEventTypeRetentionStampsExpiresAtPerType() throws Exception {
        // #181: ClickHouse's TTL is on expires_at, so per-event retention is the
        // stored expires_at per type. Write break (100s) + say (never) and read
        // the stored expiry back - this is the prod backend's per-type behaviour.
        RetentionPolicy policy = new RetentionPolicy(3600L, java.util.Map.of(
                "break", 100L, "say", RetentionPolicy.NEVER_SECONDS));
        ClickHouseRecordStore policyStore = new ClickHouseRecordStore(
                container.getHost(), container.getMappedPort(8123), "spyglass_it",
                "event_records_it", container.getUsername(), container.getPassword(), false, policy);
        try {
            // occurred must be current: break's occurred+100s expiry has to be in
            // the future or ClickHouse's TTL drops the already-expired row.
            Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
            BlockLocation loc = new BlockLocation(WORLD, "world", 91001, 64, 91001);
            Origin origin = Origin.player();
            Source source = Source.player(ALICE, "Alice");
            BlockSnapshot stone = new BlockSnapshot(org.bukkit.Material.STONE, "minecraft:stone",
                    List.of(), List.of(), List.of(), List.of(), null);
            BlockSnapshot air = new BlockSnapshot(org.bukkit.Material.AIR, "minecraft:air",
                    List.of(), List.of(), List.of(), List.of(), null);
            policyStore.save(List.of(
                    new BlockBreakRecord(UUID.randomUUID(), "break", now, now.plusSeconds(3600),
                            origin, source, loc, "t", "STONE", stone, air),
                    new ChatRecord(UUID.randomUUID(), "say", now, now.plusSeconds(3600),
                            origin, source, loc, "t", "Alice", "hi", List.of(), java.util.Map.of())));
            flushAsyncInserts();

            // Read back through the field store (same table); it reconstructs
            // expiresAt from the stored expires_at column - so this proves the
            // per-type value policyStore wrote actually landed.
            QueryResult res = store.query(new QueryRequest(
                    List.of(new QueryPredicate.Eq("source.playerId", ALICE)),
                    Sort.NEWEST_FIRST, 50, EnumSet.noneOf(Flag.class), false));
            assertThat(res.records())
                    .as("records returned (events seen: "
                            + res.records().stream().map(EventRecord::event).toList() + ")")
                    .hasSize(2);
            EventRecord brk = res.records().stream()
                    .filter(r -> r.event().equals("break")).findFirst().orElseThrow();
            EventRecord say = res.records().stream()
                    .filter(r -> r.event().equals("say")).findFirst().orElseThrow();
            assertThat(brk.expiresAt())
                    .as("break stored expires_at = occurred + its 100s retention")
                    .isEqualTo(now.plusSeconds(100L));
            assertThat(say.expiresAt())
                    .as("say stored expires_at = the clamped never ceiling (under CH's TTL range)")
                    .isEqualTo(RetentionPolicy.MAX_EXPIRY);
        } finally {
            policyStore.close();
        }
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
                        origin, source, location, "test", "STONE", stone, air),
                new BlockPlaceRecord(UUID.randomUUID(), "place", now, now.plusSeconds(3600),
                        origin, source, location, "test", "STONE", air, stone),
                new ChatRecord(UUID.randomUUID(), "say", now, now.plusSeconds(3600),
                        origin, source, location, "test", "Alice", "hello", List.of(), java.util.Map.of("channel", "#OOC")),
                new ContainerDepositRecord(UUID.randomUUID(), "deposit", now, now.plusSeconds(3600),
                        origin, source, location, "test", "DIAMOND", "CHEST", 3, 1, null, item),
                new EntityDeathRecord(UUID.randomUUID(), "death", now, now.plusSeconds(3600),
                        origin, source, location, "test", "ZOMBIE", "ZOMBIE",
                        UUID.randomUUID(), "player", "ENTITY_ATTACK", null));

        store.save(records);
        flushAsyncInserts();

        QueryRequest allEvents = new QueryRequest(
                List.of(new QueryPredicate.Eq("source.playerId", ALICE)),
                Sort.NEWEST_FIRST, 50, EnumSet.noneOf(Flag.class), false);
        QueryResult result = store.query(allEvents);
        assertThat(result.records()).hasSize(5);
        net.medievalrp.spyglass.api.event.ChatRecord chatBack = result.records().stream()
                .filter(net.medievalrp.spyglass.api.event.ChatRecord.class::isInstance)
                .map(net.medievalrp.spyglass.api.event.ChatRecord.class::cast)
                .findFirst().orElseThrow();
        assertThat(chatBack.extensions()).containsEntry("channel", "#OOC");
        QueryRequest byChannel = new QueryRequest(
                List.of(new QueryPredicate.Eq("extensions.channel", "#OOC")),
                Sort.NEWEST_FIRST, 50, EnumSet.noneOf(Flag.class), false);
        assertThat(store.query(byChannel).records()).hasSize(1);

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
                origin, source, loc, "test", "CHEST", stoneWithItem, air);
        store.save(List.of(saved));
        flushAsyncInserts();

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
                    origin, source, loc, "test", "STONE", stone, air));
        }
        store.save(many);
        flushAsyncInserts();
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
                    "test", "STONE", stone, air)));
        }
        flushAsyncInserts();

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
                origin, source, loc, "test", "CHEST", bigSnapshot, null)));
        flushAsyncInserts();

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
    void roundTripsRegisteredCustomEvent() {
        // A registered custom event (e.g. voicechat's "voice") must persist
        // and decode as a CustomRecord, searchable by a:, m:, and
        // extensions.<key>, with the bag intact on the display path.
        net.medievalrp.spyglass.api.event.EventCatalog.register("voice", "spoke");
        Instant now = Instant.now().minusSeconds(60);
        BlockLocation loc = new BlockLocation(WORLD, "world", 1, 64, 2);
        net.medievalrp.spyglass.api.event.CustomRecord rec =
                net.medievalrp.spyglass.api.event.CustomRecord.of(
                        new net.medievalrp.spyglass.api.event.RecordContext(
                                UUID.randomUUID(), now, now.plusSeconds(3600),
                                Origin.player(), Source.player(ALICE, "Alice"), loc, "test",
                                java.util.Map.of()),
                        "voice", "voice to 2 players", "hello there",
                        java.util.Map.of("voice_session_id", "42"));
        store.save(List.of(rec));
        flushAsyncInserts();

        QueryRequest byEvent = new QueryRequest(
                List.of(new QueryPredicate.Eq("event", "voice")),
                Sort.NEWEST_FIRST, 10, EnumSet.noneOf(Flag.class), false);
        var back = (net.medievalrp.spyglass.api.event.CustomRecord)
                store.query(byEvent).records().get(0);
        assertThat(back.message()).isEqualTo("hello there");
        assertThat(back.target()).isEqualTo("voice to 2 players");
        assertThat(back.extensions()).containsEntry("voice_session_id", "42");

        // extensions.<key> filtering and m: message search both hit.
        QueryRequest byExt = new QueryRequest(
                List.of(new QueryPredicate.Eq("event", "voice"),
                        new QueryPredicate.Eq("extensions.voice_session_id", "42")),
                Sort.NEWEST_FIRST, 10, EnumSet.noneOf(Flag.class), false);
        assertThat(store.query(byExt).records()).hasSize(1);

        // Display path keeps the bag (no item predicate, no post-filter).
        var summary = (net.medievalrp.spyglass.api.event.CustomRecord)
                store.querySummary(byEvent).records().get(0);
        assertThat(summary.message()).isEqualTo("hello there");
        assertThat(summary.extensions()).containsEntry("voice_session_id", "42");

        // content:/regex/ on the message column pushes down to CH match()
        // (server-side, no post-filter cap).
        QueryRequest byRegex = new QueryRequest(
                List.of(new QueryPredicate.Eq("event", "voice"),
                        new QueryPredicate.Eq("message",
                                java.util.regex.Pattern.compile("hel+o",
                                        java.util.regex.Pattern.CASE_INSENSITIVE))),
                Sort.NEWEST_FIRST, 10, EnumSet.noneOf(Flag.class), false);
        assertThat(store.query(byRegex).records()).hasSize(1);

        QueryRequest byRegexMiss = new QueryRequest(
                List.of(new QueryPredicate.Eq("event", "voice"),
                        new QueryPredicate.Eq("message",
                                java.util.regex.Pattern.compile("^goodbye$",
                                        java.util.regex.Pattern.CASE_INSENSITIVE))),
                Sort.NEWEST_FIRST, 10, EnumSet.noneOf(Flag.class), false);
        assertThat(store.query(byRegexMiss).records()).isEmpty();

        // The exact predicate m: / message: / content: build: an OR across
        // message + commandLine, each a quoted literal Pattern. commandLine
        // must be a mapped column or the whole OR drops to the in-memory
        // post-filter (where message read as null and never matched) — the
        // regression this fixes.
        java.util.regex.Pattern lit = java.util.regex.Pattern.compile(
                java.util.regex.Pattern.quote("hello"), java.util.regex.Pattern.CASE_INSENSITIVE);
        QueryRequest byContent = new QueryRequest(
                List.of(new QueryPredicate.Eq("event", "voice"),
                        new QueryPredicate.Or(List.of(
                                new QueryPredicate.Eq("message", lit),
                                new QueryPredicate.Eq("commandLine", lit)))),
                Sort.NEWEST_FIRST, 10, EnumSet.noneOf(Flag.class), false);
        assertThat(store.query(byContent).records())
                .as("m:/content: (OR message,commandLine) must match the message column")
                .hasSize(1);
    }

    @Test
    void recipientSearchPushesDownAndMatches() {
        // rcp: queries the recipients Array(UUID). It must push down via
        // has()/hasAny() — not drop to the post-filter where it matched nothing.
        UUID bob = UUID.fromString("33333333-3333-3333-3333-333333333333");
        Instant now = Instant.now().minusSeconds(60);
        store.save(List.of(new ChatRecord(
                UUID.randomUUID(), "say", now, now.plusSeconds(3600),
                Origin.player(), Source.player(ALICE, "Alice"),
                new BlockLocation(WORLD, "world", 0, 64, 0), "test",
                "hi", "hi", List.of(ALICE, bob), java.util.Map.of())));
        flushAsyncInserts();

        QueryRequest hit = new QueryRequest(
                List.of(new QueryPredicate.Eq("recipients", bob)),
                Sort.NEWEST_FIRST, 10, EnumSet.noneOf(Flag.class), false);
        assertThat(store.query(hit).records()).hasSize(1);

        QueryRequest miss = new QueryRequest(
                List.of(new QueryPredicate.Eq("recipients",
                        UUID.fromString("99999999-9999-9999-9999-999999999999"))),
                Sort.NEWEST_FIRST, 10, EnumSet.noneOf(Flag.class), false);
        assertThat(store.query(miss).records()).isEmpty();
    }

    @Test
    void roundTripsKillAndMobKillAsEntityHitRecord() {
        // kill/mob-kill reuse EntityHitRecord and the existing CH columns; the
        // catalog maps both names to it so the read path decodes them.
        Instant now = Instant.now().minusSeconds(60);
        BlockLocation loc = new BlockLocation(WORLD, "world", 1, 64, 2);
        store.save(List.of(
                new net.medievalrp.spyglass.api.event.EntityHitRecord(
                        UUID.randomUUID(), "kill", now, now.plusSeconds(3600),
                        Origin.player(), Source.player(ALICE, "Alice"), loc, "test",
                        "zombie", "zombie", UUID.randomUUID(), 6.0, false, null),
                new net.medievalrp.spyglass.api.event.EntityHitRecord(
                        UUID.randomUUID(), "mob-kill", now, now.plusSeconds(3600),
                        Origin.environment("kill:zombie"),
                        Source.entity(UUID.randomUUID(), "zombie"), loc, "test",
                        "Bob", "player", UUID.randomUUID(), 4.0, false, null)));
        flushAsyncInserts();

        QueryRequest killReq = new QueryRequest(
                List.of(new QueryPredicate.Eq("event", "kill")),
                Sort.NEWEST_FIRST, 10, EnumSet.noneOf(Flag.class), false);
        assertThat(store.query(killReq).records()).singleElement()
                .isInstanceOf(net.medievalrp.spyglass.api.event.EntityHitRecord.class);

        QueryRequest mobReq = new QueryRequest(
                List.of(new QueryPredicate.Eq("event", "mob-kill")),
                Sort.NEWEST_FIRST, 10, EnumSet.noneOf(Flag.class), false);
        assertThat(store.query(mobReq).records()).singleElement()
                .isInstanceOf(net.medievalrp.spyglass.api.event.EntityHitRecord.class);
    }

    @Test
    void summaryQueryRetainsItemProjectionsForHover() {
        // Feature: the search hover shows an item's custom name / lore /
        // enchants. The display path (querySummary) must carry the item even
        // when no item predicate triggers the post-filter hydrate — a plain
        // event filter must still come back with afterItem populated.
        Instant now = Instant.now().minusSeconds(60);
        BlockLocation loc = new BlockLocation(WORLD, "world", 0, 64, 0);
        StoredItem stormCaller = new StoredItem(0, "IRON_HORSE_ARMOR", "AAAA",
                "Storm Caller",
                List.of("Forged in the primordial deep"),
                List.of("protection=4"));
        store.save(List.of(new net.medievalrp.spyglass.api.event.ContainerDepositRecord(
                UUID.randomUUID(), "deposit", now, now.plusSeconds(3600),
                Origin.player(), Source.player(ALICE, "Alice"),
                loc, "test", "IRON_HORSE_ARMOR", "CHEST", 0, 1, null, stormCaller)));
        flushAsyncInserts();

        QueryRequest q = new QueryRequest(
                List.of(new QueryPredicate.Eq("event", "deposit")),
                Sort.NEWEST_FIRST, 10, EnumSet.noneOf(Flag.class), false);
        var deposit = (net.medievalrp.spyglass.api.event.ContainerDepositRecord)
                store.querySummary(q).records().get(0);
        assertThat(deposit.afterItem()).isNotNull();
        assertThat(deposit.afterItem().name()).isEqualTo("Storm Caller");
        assertThat(deposit.afterItem().lore()).contains("Forged in the primordial deep");
        assertThat(deposit.afterItem().enchants()).contains("protection=4");
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
                loc, "test", "STONE", stone, air);

        store.save(List.of(record));
        store.save(List.of(record));
        flushAsyncInserts();

        // Force a merge so dedup is observable; in production this
        // happens lazily on background merges.
        store.client().execute(
                "OPTIMIZE TABLE `spyglass_it`.`event_records_it` FINAL DEDUPLICATE")
                .get(60, java.util.concurrent.TimeUnit.SECONDS).close();

        QueryRequest byId = new QueryRequest(
                List.of(new QueryPredicate.Eq("id", recordId)),
                Sort.NEWEST_FIRST, 10, EnumSet.noneOf(Flag.class), false);
        assertThat(store.query(byId).records())
                .as("ReplacingMergeTree must collapse duplicate ids on merge")
                .hasSize(1);
    }

    @Test
    void streamRollbackMatchesQueryPageWindowForWindow() {
        // #19: the rollback engine reads via streamRollback. It must
        // walk the result set in the same order and the same cursor
        // steps as queryPage — no row duplicated or skipped across a
        // window boundary — so swapping the read entry point can never
        // change what a rollback sees. Includes a same-instant cluster
        // to exercise the (occurred, id) keyset tie-break.
        Instant base = Instant.now().minusSeconds(600);
        BlockSnapshot air = new BlockSnapshot(
                org.bukkit.Material.AIR, "minecraft:air",
                List.of(), List.of(), List.of(), List.of(), null);
        BlockSnapshot stone = new BlockSnapshot(
                org.bukkit.Material.STONE, "minecraft:stone",
                List.of(), List.of(), List.of(), List.of(), null);
        Origin origin = Origin.player();
        Source source = Source.player(ALICE, "Alice");
        List<EventRecord> many = new java.util.ArrayList<>();
        for (int i = 0; i < 21; i++) {
            many.add(new BlockBreakRecord(UUID.randomUUID(), "break",
                    base.plusSeconds(i), base.plusSeconds(7200),
                    origin, source,
                    new BlockLocation(WORLD, "world", i, 64, -i),
                    "test", "STONE", stone, air));
        }
        for (int i = 0; i < 3; i++) {
            many.add(new BlockBreakRecord(UUID.randomUUID(), "break",
                    base.plusSeconds(30), base.plusSeconds(7200),
                    origin, source,
                    new BlockLocation(WORLD, "world", 100 + i, 64, 0),
                    "test", "STONE", stone, air));
        }
        store.save(many);
        flushAsyncInserts();

        QueryRequest request = new QueryRequest(
                List.of(new QueryPredicate.Eq("event", "break")),
                Sort.NEWEST_FIRST, 1000, EnumSet.noneOf(Flag.class), false);

        List<UUID> pagedIds = new java.util.ArrayList<>();
        QueryPage.Cursor pageCursor = null;
        int pageRounds = 0;
        while (true) {
            QueryPage page = store.queryPage(request, pageCursor, 7);
            page.records().forEach(r -> pagedIds.add(r.id()));
            pageRounds++;
            if (page.next() == null) {
                break;
            }
            pageCursor = page.next();
        }

        List<UUID> streamedIds = new java.util.ArrayList<>();
        QueryPage.Cursor streamCursor = null;
        int streamRounds = 0;
        while (true) {
            QueryPage.Cursor next = store.streamRollback(
                    request, streamCursor, 7, r -> streamedIds.add(r.id()));
            streamRounds++;
            if (next == null) {
                break;
            }
            streamCursor = next;
        }

        assertThat(streamedIds)
                .hasSize(24)
                .doesNotHaveDuplicates()
                .containsExactlyElementsOf(pagedIds);
        assertThat(streamRounds).isEqualTo(pageRounds);
    }

    @Test
    void streamRollbackEffectsDecodesSimpleBlocksLeanly() {
        // #67: the rollback engine reads via streamRollbackEffects. A simple
        // block-replace must arrive as primitives (block-data + expected),
        // in the correct direction; a tile-entity block falls back to a
        // built effect.
        Instant base = Instant.now().minusSeconds(600);
        BlockSnapshot air = new BlockSnapshot(
                org.bukkit.Material.AIR, "minecraft:air",
                List.of(), List.of(), List.of(), List.of(), null);
        BlockSnapshot stone = new BlockSnapshot(
                org.bukkit.Material.STONE, "minecraft:stone",
                List.of(), List.of(), List.of(), List.of(), null);
        BlockSnapshot sign = new BlockSnapshot(
                org.bukkit.Material.OAK_SIGN, "minecraft:oak_sign",
                List.of(), List.of("hello"), List.of(), List.of(), null);
        Origin origin = Origin.player();
        Source source = Source.player(ALICE, "Alice");
        List<EventRecord> records = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            records.add(new BlockBreakRecord(UUID.randomUUID(), "break",
                    base.plusSeconds(i), base.plusSeconds(7200), origin, source,
                    new BlockLocation(WORLD, "world", i, 64, 0), "test", "STONE", stone, air));
        }
        // A tile-entity block: before=sign (non-simple) => object path.
        records.add(new BlockBreakRecord(UUID.randomUUID(), "break",
                base.plusSeconds(10), base.plusSeconds(7200), origin, source,
                new BlockLocation(WORLD, "world", 50, 64, 0), "test", "OAK_SIGN", sign, air));
        store.save(records);
        flushAsyncInserts();

        QueryRequest request = new QueryRequest(
                List.of(new QueryPredicate.Eq("event", "break")),
                Sort.NEWEST_FIRST, 1000, EnumSet.noneOf(Flag.class), false);

        List<int[]> blocks = new java.util.ArrayList<>();
        List<String> blockData = new java.util.ArrayList<>();
        List<String> expectedData = new java.util.ArrayList<>();
        java.util.List<net.medievalrp.spyglass.api.rollback.RollbackEffect> complex =
                new java.util.ArrayList<>();
        int[] skips = {0};
        store.streamRollbackEffects(request, null, 1000, true,
                new RecordStore.RollbackEffectSink() {
                    @Override
                    public void block(UUID world, int x, int y, int z, String data,
                                      String expected, Instant occurred, UUID id) {
                        blocks.add(new int[]{x, y, z});
                        blockData.add(data);
                        expectedData.add(expected);
                    }

                    @Override
                    public void complex(net.medievalrp.spyglass.api.rollback.RollbackEffect effect,
                                        Instant occurred, UUID id) {
                        complex.add(effect);
                    }

                    @Override
                    public void skip(Instant occurred, UUID id) {
                        skips[0]++;
                    }
                });

        assertThat(blocks).hasSize(5);
        assertThat(skips[0]).isZero();
        // rollback writes the before-state (stone). The expected side is
        // never read under force-overwrite (#69) — the direction-specific
        // SELECT omits after_* entirely on a rollback — so expectedData is
        // null. (This is the read-churn cut: ~half the per-row string decode.)
        assertThat(blockData).containsOnly("minecraft:stone");
        assertThat(expectedData).containsOnly((String) null);
        // The sign block fell back to a built BlockReplace effect.
        assertThat(complex).hasSize(1);
        assertThat(complex.get(0))
                .isInstanceOf(net.medievalrp.spyglass.api.rollback.RollbackEffect.BlockReplace.class);
    }

    @Test
    void streamRollbackEffectsDecodesContainerSlotWriteToComplex() {
        // #67: a container deposit is not a block-replace — it must decode
        // to a ContainerSlotWrite via the object path, in the right
        // direction (rollback undoes the deposit: expect the deposited item,
        // restore the pre-deposit slot).
        Instant now = Instant.now().minusSeconds(120);
        net.medievalrp.spyglass.api.event.StoredItem diamond =
                new net.medievalrp.spyglass.api.event.StoredItem(3, "DIAMOND", "data");
        store.save(List.of(new net.medievalrp.spyglass.api.event.ContainerDepositRecord(
                UUID.randomUUID(), "deposit", now, now.plusSeconds(7200),
                Origin.player(), Source.player(ALICE, "Alice"),
                new BlockLocation(WORLD, "world", 5, 64, 5), "test", "CHEST",
                "CHEST", 3, 1, null, diamond)));
        flushAsyncInserts();

        QueryRequest request = new QueryRequest(
                List.of(new QueryPredicate.Eq("event", "deposit")),
                Sort.NEWEST_FIRST, 1000, EnumSet.noneOf(Flag.class), false);

        java.util.List<net.medievalrp.spyglass.api.rollback.RollbackEffect> complex =
                new java.util.ArrayList<>();
        int[] blocks = {0};
        store.streamRollbackEffects(request, null, 1000, true,
                new RecordStore.RollbackEffectSink() {
                    @Override
                    public void block(UUID world, int x, int y, int z, String data,
                                      String expected, Instant occurred, UUID id) {
                        blocks[0]++;
                    }

                    @Override
                    public void complex(net.medievalrp.spyglass.api.rollback.RollbackEffect effect,
                                        Instant occurred, UUID id) {
                        complex.add(effect);
                    }

                    @Override
                    public void skip(Instant occurred, UUID id) {
                    }
                });

        assertThat(blocks[0]).isZero();
        assertThat(complex).hasSize(1);
        assertThat(complex.get(0))
                .isInstanceOf(net.medievalrp.spyglass.api.rollback.RollbackEffect.ContainerSlotWrite.class);
        var slot = (net.medievalrp.spyglass.api.rollback.RollbackEffect.ContainerSlotWrite) complex.get(0);
        assertThat(slot.slot()).isEqualTo(3);
        // rollback: expect the deposited diamond, restore the empty slot.
        assertThat(slot.expectedCurrent()).isNotNull();
        assertThat(slot.expectedCurrent().material()).isEqualTo("DIAMOND");
        assertThat(slot.replacement()).isNull();
    }
    @Test
    void itemFieldFiltersPostFilterInMemory() {
        // #32: iname:/ilore:/ench: paths live inside opaque BSON on CH.
        // The store pushes what it can and applies the item predicates
        // in memory — this drives the exact Or-of-paths shape the params
        // emit, Pattern values included.
        Instant now = Instant.now().minusSeconds(60);
        StoredItem excaliblur = new StoredItem(0, "DIAMOND_SWORD", "AAAA",
                "Excaliblur",
                List.of("Forged in primordial fire"),
                List.of("sharpness=5"),
                "{quest:\"primordial_rite\"}");
        StoredItem mundane = new StoredItem(0, "IRON_SWORD", "BBBB",
                null, List.of(), List.of());
        store.save(List.of(
                new net.medievalrp.spyglass.api.event.ItemDropRecord(
                        UUID.randomUUID(), "drop", now, now.plusSeconds(3600),
                        Origin.player(), Source.player(ALICE, "Alice"),
                        new BlockLocation(WORLD, "world", 1, 64, 1), "test",
                        "DIAMOND_SWORD", 1, excaliblur),
                new net.medievalrp.spyglass.api.event.ItemDropRecord(
                        UUID.randomUUID(), "drop", now, now.plusSeconds(3600),
                        Origin.player(), Source.player(ALICE, "Alice"),
                        new BlockLocation(WORLD, "world", 2, 64, 2), "test",
                        "IRON_SWORD", 1, mundane)));
        flushAsyncInserts();

        java.util.function.Function<QueryPredicate, QueryRequest> req = p ->
                new QueryRequest(List.of(
                        new QueryPredicate.Eq("source.playerId", ALICE), p),
                        Sort.NEWEST_FIRST, 100, EnumSet.noneOf(Flag.class), false);
        java.util.function.BiFunction<String, String, QueryPredicate> anyItem = (sub, term) -> {
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                    java.util.regex.Pattern.quote(term), java.util.regex.Pattern.CASE_INSENSITIVE);
            List<QueryPredicate> clauses = new java.util.ArrayList<>();
            for (String path : List.of("item", "beforeItem", "afterItem",
                    "originalBlock.containerItems", "newBlock.containerItems")) {
                clauses.add(new QueryPredicate.Eq(path + "." + sub, pattern));
            }
            return new QueryPredicate.Or(List.copyOf(clauses));
        };

        assertThat(store.query(req.apply(anyItem.apply("name", "excali"))).records())
                .hasSize(1)
                .allMatch(r -> "DIAMOND_SWORD".equals(r.target()));
        assertThat(store.query(req.apply(anyItem.apply("lore", "primordial"))).records())
                .hasSize(1);
        assertThat(store.query(req.apply(anyItem.apply("enchants", "sharpness"))).records())
                .hasSize(1);
        // itags: resolves from the decoded item blob via the in-memory
        // post-filter, the same as iname/ilore/ench (#140).
        assertThat(store.query(req.apply(anyItem.apply("tags", "primordial_rite"))).records())
                .hasSize(1)
                .allMatch(r -> "DIAMOND_SWORD".equals(r.target()));
        assertThat(store.query(req.apply(anyItem.apply("tags", "nonexistent"))).records())
                .isEmpty();
        assertThat(store.query(req.apply(anyItem.apply("name", "nonexistent"))).records())
                .isEmpty();
        // Summary entry point must hydrate + filter identically.
        assertThat(store.querySummary(req.apply(anyItem.apply("name", "excali"))).records())
                .hasSize(1);
    }

}
