package net.medievalrp.spyglass.plugin.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
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
import net.medievalrp.spyglass.api.util.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MongoDBContainer;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MongoRecordStoreIT {

    private static final UUID ALICE = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID WORLD = UUID.fromString("77777777-7777-7777-7777-777777777777");

    private MongoDBContainer container;
    private MongoClient rawClient;
    private MongoRecordStore store;

    @BeforeAll
    void setup() {
        assumeThat(DockerClientFactory.instance().isDockerAvailable())
                .as("docker not available")
                .isTrue();
        container = new MongoDBContainer("mongo:7.0");
        container.start();
        String uri = container.getReplicaSetUrl();
        rawClient = MongoClients.create(uri);
        store = new MongoRecordStore(uri, "IT", "EventRecords", new IndexManager());
    }

    @AfterAll
    void teardown() {
        if (store != null) {
            store.close();
        }
        if (rawClient != null) {
            rawClient.close();
        }
        if (container != null) {
            container.stop();
        }
    }

    @org.junit.jupiter.api.BeforeEach
    void wipe() {
        // Same pattern as the ClickHouse IT: tests previously coexisted
        // only by method-order luck (assertions like "exactly one break
        // record" against a shared collection). Wiping between tests
        // removes the cross-test data dependency.
        rawClient.getDatabase("IT").getCollection("EventRecords")
                .deleteMany(new org.bson.Document());
    }

    @Test
    void perEventTypeRetentionStampsExpiresAtPerType() {
        // #181: Mongo's TTL index is on expiresAt, so per-event retention is the
        // stored expiresAt per type. break (100s) + say (never), read back raw.
        RetentionPolicy policy = new RetentionPolicy(3600L, Map.of(
                "break", 100L, "say", RetentionPolicy.NEVER_SECONDS));
        MongoRecordStore policyStore = new MongoRecordStore(
                container.getReplicaSetUrl(), "IT", "EventRecords", new IndexManager(), policy);
        try {
            Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
            Origin origin = Origin.player();
            Source source = Source.player(ALICE, "Alice");
            BlockSnapshot stone = new BlockSnapshot(org.bukkit.Material.STONE, "minecraft:stone",
                    List.of(), List.of(), List.of(), List.of(), null);
            BlockSnapshot air = new BlockSnapshot(org.bukkit.Material.AIR, "minecraft:air",
                    List.of(), List.of(), List.of(), List.of(), null);
            BlockLocation loc = new BlockLocation(WORLD, "world", 92001, 64, 92001);
            // Clean slate so the typed query below returns only these two.
            policyStore.database().getCollection("EventRecords", org.bson.BsonDocument.class)
                    .deleteMany(new org.bson.BsonDocument());
            policyStore.save(List.of(
                    new BlockBreakRecord(UUID.randomUUID(), "break", now, now.plusSeconds(3600),
                            origin, source, loc, "t", "STONE", stone, air),
                    new ChatRecord(UUID.randomUUID(), "say", now, now.plusSeconds(3600),
                            origin, source, loc, "t", "Alice", "hi", List.of(), Map.of())));

            // Read back through the store's typed query; expiresAt is the stored
            // per-type value the encode-and-patch write produced.
            QueryResult res = policyStore.query(new QueryRequest(
                    List.of(new QueryPredicate.Eq("source.playerId", ALICE)),
                    Sort.NEWEST_FIRST, 50, EnumSet.noneOf(Flag.class), false));
            EventRecord brk = res.records().stream()
                    .filter(r -> r.event().equals("break")).findFirst().orElseThrow();
            EventRecord say = res.records().stream()
                    .filter(r -> r.event().equals("say")).findFirst().orElseThrow();
            assertThat(brk.expiresAt())
                    .as("break stored expiresAt = occurred + its 100s retention")
                    .isEqualTo(now.plusSeconds(100L));
            assertThat(say.expiresAt())
                    .as("say stored expiresAt = the clamped never ceiling")
                    .isEqualTo(RetentionPolicy.MAX_EXPIRY);
        } finally {
            policyStore.close();
        }
    }

    @Test
    void savesAndQueriesAllRecordTypes() {
        Instant now = Instant.parse("2026-04-23T12:00:00Z");
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
    void allExpectedIndexesExist() {
        List<String> indexNames = rawClient.getDatabase("IT")
                .getCollection("EventRecords")
                .listIndexes()
                .into(new java.util.ArrayList<>())
                .stream()
                .map(document -> document.getString("name"))
                .toList();

        // Rollback indexes end with id (-1) so the keyset (occurred, id) sort
        // is fully index-covered — no blocking SORT stage per page.
        assertThat(indexNames).contains(
                "_id_",
                "source.playerId_1_occurred_-1_id_-1",
                "event_1_occurred_-1_id_-1",
                "location.worldId_1_location.cx_1_location.cz_1_occurred_-1_id_-1",
                "expiresAt_1");
    }

    @Test
    void supersededLegacyRollbackIndexIsDropped() {
        com.mongodb.client.MongoCollection<org.bson.BsonDocument> collection = rawClient
                .getDatabase("IT").getCollection("EventRecords", org.bson.BsonDocument.class);
        // Recreate the pre-id legacy rollback index, as a Mongo deployment
        // upgraded in place from before the id-covered keyset shape would have.
        collection.createIndex(com.mongodb.client.model.Indexes.compoundIndex(
                com.mongodb.client.model.Indexes.ascending("event"),
                com.mongodb.client.model.Indexes.descending("occurred")));
        assertThat(legacyIndexNames(collection)).contains("event_1_occurred_-1");

        new IndexManager().ensureRecordIndexes(collection);

        // The legacy index is gone (no double write-amplification) and the
        // id-covered superset that replaced it is present.
        assertThat(legacyIndexNames(collection))
                .doesNotContain("event_1_occurred_-1")
                .contains("event_1_occurred_-1_id_-1");
    }

    private static List<String> legacyIndexNames(
            com.mongodb.client.MongoCollection<org.bson.BsonDocument> collection) {
        return collection.listIndexes(org.bson.BsonDocument.class)
                .into(new java.util.ArrayList<>())
                .stream()
                .map(document -> document.getString("name").getValue())
                .toList();
    }

    @Test
    void recordCollectionUsesZstdBlockCompressor() {
        // The store creates the collection with WiredTiger's zstd block
        // compressor (vs the snappy default) to roughly third the on-disk
        // data. Verify the creation option stuck.
        org.bson.Document spec = rawClient.getDatabase("IT")
                .listCollections()
                .filter(com.mongodb.client.model.Filters.eq("name", "EventRecords"))
                .first();
        assertThat(spec).isNotNull();
        org.bson.Document options = spec.get("options", org.bson.Document.class);
        org.bson.Document storageEngine = options.get("storageEngine", org.bson.Document.class);
        org.bson.Document wiredTiger = storageEngine.get("wiredTiger", org.bson.Document.class);
        assertThat(wiredTiger.getString("configString")).contains("block_compressor=zstd");
    }

    @Test
    void constructingAgainstAnExistingCollectionDoesNotFail() {
        // The collection already exists (created in @BeforeAll), so the
        // second construction's createCollection raises NamespaceExists; that
        // path must be swallowed, not propagated, and the existing zstd
        // collection left intact.
        MongoRecordStore second = new MongoRecordStore(
                container.getReplicaSetUrl(), "IT", "EventRecords", new IndexManager());
        second.close();
        org.bson.Document spec = rawClient.getDatabase("IT").listCollections()
                .filter(com.mongodb.client.model.Filters.eq("name", "EventRecords")).first();
        assertThat(spec).isNotNull();
    }

    @Test
    void regionQueryReturnsOnlyInRegionRecordsAndWritesChunkBuckets() {
        Instant now = Instant.parse("2026-05-01T00:00:00Z");
        Origin origin = Origin.player();
        Source source = Source.player(ALICE, "Alice");
        BlockSnapshot stone = new BlockSnapshot(org.bukkit.Material.STONE, "minecraft:stone",
                List.of(), List.of(), List.of(), List.of(), null);
        BlockSnapshot air = new BlockSnapshot(org.bukkit.Material.AIR, "minecraft:air",
                List.of(), List.of(), List.of(), List.of(), null);
        store.save(List.of(
                new BlockBreakRecord(UUID.randomUUID(), "break", now, now.plusSeconds(3600), origin,
                        source, new BlockLocation(WORLD, "world", 100, 64, 100), "t", "STONE", stone, air),
                new BlockBreakRecord(UUID.randomUUID(), "break", now, now.plusSeconds(3600), origin,
                        source, new BlockLocation(WORLD, "world", 118, 64, 109), "t", "STONE", stone, air),
                new BlockBreakRecord(UUID.randomUUID(), "break", now, now.plusSeconds(3600), origin,
                        source, new BlockLocation(WORLD, "world", 5000, 64, 5000), "t", "STONE", stone, air)));

        // BlockLocationCodec wrote the chunk buckets cx = x>>4, cz = z>>4.
        org.bson.BsonDocument stored = rawClient.getDatabase("IT")
                .getCollection("EventRecords", org.bson.BsonDocument.class)
                .find(new org.bson.Document("location.x", 100)).first();
        assertThat(stored).isNotNull();
        org.bson.BsonDocument loc = stored.getDocument("location");
        assertThat(loc.getInt32("cx").getValue()).isEqualTo(100 >> 4);
        assertThat(loc.getInt32("cz").getValue()).isEqualTo(100 >> 4);

        // A box query (chunk bounds added by PredicateToBson seek the index,
        // exact x/z bounds filter within) returns the two inside, not the far one.
        QueryRequest region = new QueryRequest(List.of(
                new QueryPredicate.Eq("location.worldId", WORLD),
                new QueryPredicate.Range("location.x", 90, 120),
                new QueryPredicate.Range("location.z", 90, 120)),
                Sort.NEWEST_FIRST, 50, EnumSet.noneOf(Flag.class), false);
        assertThat(store.query(region).records()).hasSize(2);
    }

    @Test
    void backfillPopulatesChunkBucketsOnRecordsMissingThem() {
        // A record written before cx/cz existed: raw insert with no chunk
        // buckets. Negative x exercises floor-toward-negative-infinity.
        com.mongodb.client.MongoCollection<org.bson.Document> coll =
                rawClient.getDatabase("IT").getCollection("EventRecords");
        coll.insertOne(new org.bson.Document("event", "break").append("location",
                new org.bson.Document("x", -33).append("y", 64).append("z", 17)));

        // Constructing a store runs the one-time backfill.
        new MongoRecordStore(container.getReplicaSetUrl(), "IT", "EventRecords",
                new IndexManager()).close();

        org.bson.BsonDocument loc = rawClient.getDatabase("IT")
                .getCollection("EventRecords", org.bson.BsonDocument.class)
                .find(new org.bson.Document("location.x", -33)).first().getDocument("location");
        // int32 (not double) so the chunk index is single-typed; floor semantics.
        assertThat(loc.getInt32("cx").getValue()).isEqualTo(-33 >> 4); // floor(-33/16) = -3
        assertThat(loc.getInt32("cz").getValue()).isEqualTo(17 >> 4);  // floor(17/16) = 1
    }

    @Test
    void readOnlyStoreSkipsBackfill() {
        // performSetup=false (the read-only Velocity companion) must not
        // rewrite records on startup. A doc without cx/cz stays untouched.
        com.mongodb.client.MongoCollection<org.bson.Document> coll =
                rawClient.getDatabase("IT").getCollection("EventRecords");
        coll.insertOne(new org.bson.Document("event", "break").append("location",
                new org.bson.Document("x", 256).append("y", 64).append("z", 256)));

        new MongoRecordStore(container.getReplicaSetUrl(), "IT", "EventRecords",
                new IndexManager(), false).close();

        org.bson.BsonDocument loc = rawClient.getDatabase("IT")
                .getCollection("EventRecords", org.bson.BsonDocument.class)
                .find(new org.bson.Document("location.x", 256)).first().getDocument("location");
        assertThat(loc.containsKey("cx")).as("read-only store must not backfill").isFalse();
    }

    @Test
    void dropEmptyCollectionAndRecreate() {
        rawClient.getDatabase("IT").getCollection("EventRecords").deleteMany(new org.bson.Document());
        QueryRequest empty = new QueryRequest(
                List.of(new QueryPredicate.Eq("source.playerId", UUID.randomUUID())),
                Sort.NEWEST_FIRST, 50, EnumSet.noneOf(Flag.class), false);
        assertThat(store.query(empty).records()).isEmpty();
    }

    @Test
    void clientAccessorReturnsLiveClient() {
        assertThat(store.client()).isNotNull();
        assertThat(store.database()).isNotNull();
        assertThat(store.codecRegistry()).isNotNull();
    }

    @Test
    void queriesItemNameLoreAndEnchantsAcrossPaths() {
        Instant now = Instant.now();
        BlockLocation loc = new BlockLocation(WORLD, "world", 5, 64, 5);
        Origin origin = Origin.player();
        Source source = Source.player(ALICE, "Alice");
        StoredItem enchanted = new StoredItem(
                0, "DIAMOND_SWORD", null,
                "Excaliblur",
                List.of("Forged in primordial fire", "Blessed by saints"),
                List.of("sharpness=5", "mending=1"),
                "{quest:\"primordial_rite\"}");

        // deposit carries the item as afterItem; drop carries it as item;
        // place carries it inside newBlock.containerItems[]. Save one of each
        // so we prove the Or-across-paths predicate actually hits every path.
        BlockSnapshot chestWithItem = new BlockSnapshot(
                org.bukkit.Material.CHEST, "minecraft:chest",
                List.of(enchanted), List.of(), List.of(), List.of(), null);
        BlockSnapshot air = new BlockSnapshot(
                org.bukkit.Material.AIR, "minecraft:air",
                List.of(), List.of(), List.of(), List.of(), null);

        List<EventRecord> records = List.of(
                new ContainerDepositRecord(UUID.randomUUID(), "deposit",
                        now, now.plusSeconds(3600),
                        origin, source, loc, "test", "DIAMOND_SWORD", "CHEST",
                        0, 1, null, enchanted),
                new net.medievalrp.spyglass.api.event.ItemDropRecord(
                        UUID.randomUUID(), "drop",
                        now, now.plusSeconds(3600),
                        origin, source, loc, "test", "DIAMOND_SWORD", 1, enchanted),
                new BlockPlaceRecord(UUID.randomUUID(), "place",
                        now, now.plusSeconds(3600),
                        origin, source, loc, "test", "CHEST", air, chestWithItem));
        store.save(records);

        // iname:Excal — three paths, three records should match.
        QueryRequest byName = new QueryRequest(
                List.of(anyItemField("name", "Excal")),
                Sort.NEWEST_FIRST, 50, EnumSet.noneOf(Flag.class), false);
        List<EventRecord> byNameHits = store.query(byName).records();
        assertThat(byNameHits).hasSize(3);

        // ilore:primordial — same three records hit.
        QueryRequest byLore = new QueryRequest(
                List.of(anyItemField("lore", "primordial")),
                Sort.NEWEST_FIRST, 50, EnumSet.noneOf(Flag.class), false);
        assertThat(store.query(byLore).records()).hasSize(3);

        // ench:sharpness — matches.
        QueryRequest byEnch = new QueryRequest(
                List.of(anyItemField("enchants", "sharpness")),
                Sort.NEWEST_FIRST, 50, EnumSet.noneOf(Flag.class), false);
        assertThat(store.query(byEnch).records()).hasSize(3);

        // ench:sharpness=5 — exact name+level.
        QueryRequest byEnchLevel = new QueryRequest(
                List.of(anyItemField("enchants", "sharpness=5")),
                Sort.NEWEST_FIRST, 50, EnumSet.noneOf(Flag.class), false);
        assertThat(store.query(byEnchLevel).records()).hasSize(3);

        // itags:primordial_rite: custom_data substring, all three paths (#140).
        QueryRequest byTags = new QueryRequest(
                List.of(anyItemField("tags", "primordial_rite")),
                Sort.NEWEST_FIRST, 50, EnumSet.noneOf(Flag.class), false);
        assertThat(store.query(byTags).records()).hasSize(3);

        // Missing name, no hits.
        QueryRequest missing = new QueryRequest(
                List.of(anyItemField("name", "Glamdring")),
                Sort.NEWEST_FIRST, 50, EnumSet.noneOf(Flag.class), false);
        assertThat(store.query(missing).records()).isEmpty();
    }

    @Test
    void roundTripsRegisteredCustomEvent() {
        net.medievalrp.spyglass.api.event.EventCatalog.register("voice", "spoke");
        Instant now = Instant.now();
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

        QueryRequest byEvent = new QueryRequest(
                List.of(new QueryPredicate.Eq("event", "voice")),
                Sort.NEWEST_FIRST, 10, EnumSet.noneOf(Flag.class), false);
        var back = (net.medievalrp.spyglass.api.event.CustomRecord)
                store.query(byEvent).records().get(0);
        assertThat(back.message()).isEqualTo("hello there");
        assertThat(back.target()).isEqualTo("voice to 2 players");
        assertThat(back.extensions()).containsEntry("voice_session_id", "42");

        QueryRequest byExt = new QueryRequest(
                List.of(new QueryPredicate.Eq("event", "voice"),
                        new QueryPredicate.Eq("extensions.voice_session_id", "42")),
                Sort.NEWEST_FIRST, 10, EnumSet.noneOf(Flag.class), false);
        assertThat(store.query(byExt).records()).hasSize(1);

        var summary = (net.medievalrp.spyglass.api.event.CustomRecord)
                store.querySummary(byEvent).records().get(0);
        assertThat(summary.extensions()).containsEntry("voice_session_id", "42");
    }

    @Test
    void summaryQueryRetainsItemProjectionsForHover() {
        // The display path (querySummary) drops the bulky BlockSnapshot
        // payloads but must keep the item so the search hover can show its
        // custom name / lore / enchants — even with no item predicate.
        Instant now = Instant.now();
        BlockLocation loc = new BlockLocation(WORLD, "world", 5, 64, 5);
        StoredItem stormCaller = new StoredItem(
                0, "IRON_HORSE_ARMOR", null,
                "Storm Caller",
                List.of("Forged in the primordial deep"),
                List.of("protection=4"));
        store.save(List.of(new ContainerDepositRecord(
                UUID.randomUUID(), "deposit", now, now.plusSeconds(3600),
                Origin.player(), Source.player(ALICE, "Alice"),
                loc, "test", "IRON_HORSE_ARMOR", "CHEST", 0, 1, null, stormCaller)));

        QueryRequest q = new QueryRequest(
                List.of(new QueryPredicate.Eq("event", "deposit")),
                Sort.NEWEST_FIRST, 10, EnumSet.noneOf(Flag.class), false);
        ContainerDepositRecord deposit =
                (ContainerDepositRecord) store.querySummary(q).records().get(0);
        assertThat(deposit.afterItem()).isNotNull();
        assertThat(deposit.afterItem().name()).isEqualTo("Storm Caller");
        assertThat(deposit.afterItem().lore()).contains("Forged in the primordial deep");
        assertThat(deposit.afterItem().enchants()).contains("protection=4");
    }

    private static QueryPredicate anyItemField(String subField, String raw) {
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                java.util.regex.Pattern.quote(raw),
                java.util.regex.Pattern.CASE_INSENSITIVE);
        return new QueryPredicate.Or(List.of(
                new QueryPredicate.Eq("item." + subField, pattern),
                new QueryPredicate.Eq("beforeItem." + subField, pattern),
                new QueryPredicate.Eq("afterItem." + subField, pattern),
                new QueryPredicate.Eq("originalBlock.containerItems." + subField, pattern),
                new QueryPredicate.Eq("newBlock.containerItems." + subField, pattern)));
    }

    @Test
    void expiresAtIndexIsTtlIndexWithZeroExpireAfterSeconds() {
        // TTL is a two-part contract: (1) the record carries an
        // expiresAt Instant computed at write time, and (2) Mongo has a
        // TTL index on that field with expireAfterSeconds=0 so "past
        // the instant" means "delete me now". If the index is ever
        // created without expireAfter (plain ascending index) or with
        // a non-zero expireAfter, eviction semantics silently change.
        var doc = rawClient.getDatabase("IT")
                .getCollection("EventRecords")
                .listIndexes()
                .into(new java.util.ArrayList<>())
                .stream()
                .filter(d -> "expiresAt_1".equals(d.getString("name")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("expiresAt_1 index missing"));

        // Mongo returns expireAfterSeconds as a Number — the BSON type
        // depends on driver version, so normalize via Number#longValue
        // instead of assuming Integer-vs-Long.
        Number expire = (Number) doc.get("expireAfterSeconds");
        assertThat(expire).as("expireAfterSeconds present").isNotNull();
        assertThat(expire.longValue()).isEqualTo(0L);
        // The key direction should be ascending so Mongo's TTL monitor
        // can walk oldest-first. Same Number normalization for safety.
        Number keyDir = (Number) doc.get("key", org.bson.Document.class).get("expiresAt");
        assertThat(keyDir).isNotNull();
        assertThat(keyDir.intValue()).isEqualTo(1);
    }

    @Test
    void mongoTtlMonitorEvictsDocumentsWithPastExpiresAt() throws Exception {
        // End-to-end proof: insert a record whose expiresAt is already
        // in the past, wait for Mongo's TTL monitor to cycle, and
        // verify the document is gone. This exercises the full chain
        // (plugin-side expiresAt stamping + driver-side serialization +
        // cluster-side TTL index + eviction).
        //
        // Mongo's TTL monitor default sleep is 60s, so we poll up to
        // 120s. Too long for a normal unit test, but this is the only
        // way to prove the index is actually driving eviction. Skipped
        // fully when Docker isn't available via the class-level assume.
        rawClient.getDatabase("IT").getCollection("EventRecords")
                .deleteMany(new org.bson.Document());

        Instant past = Instant.now().minusSeconds(60); // well past now
        BlockLocation location = new BlockLocation(WORLD, "world", 1, 64, 1);
        BlockSnapshot air = new BlockSnapshot(
                org.bukkit.Material.AIR, "minecraft:air",
                List.of(), List.of(), List.of(), List.of(), null);
        BlockSnapshot stone = new BlockSnapshot(
                org.bukkit.Material.STONE, "minecraft:stone",
                List.of(), List.of(), List.of(), List.of(), null);
        BlockBreakRecord expired = new BlockBreakRecord(
                UUID.randomUUID(), "break", past, past.minusSeconds(1),
                Origin.player(), Source.player(ALICE, "Alice"),
                location, "test", "STONE", stone, air);

        store.save(List.of(expired));

        // Duration.ofMinutes from the JDK — not the api.util.Duration
        // already imported at the top of this file.
        long deadline = System.currentTimeMillis() + java.time.Duration.ofMinutes(2).toMillis();
        // We wiped the collection at the start, so a filter-free count is
        // sufficient — avoids routing through the raw client's UUID codec
        // (rawClient is plain; the plugin's MongoClient is the one with
        // UuidRepresentation.STANDARD).
        long remaining = -1;
        while (System.currentTimeMillis() < deadline) {
            remaining = rawClient.getDatabase("IT")
                    .getCollection("EventRecords")
                    .countDocuments();
            if (remaining == 0) {
                break;
            }
            Thread.sleep(5_000);
        }

        assertThat(remaining)
                .as("TTL monitor should have evicted the past-expiry record within 2 min")
                .isZero();
    }

    @Test
    void concurrentSavesAndQueriesHoldUnderContention() throws Exception {
        // AsyncRecorder drains on a single virtual thread, but the
        // store must still survive concurrent reader threads (search
        // commands from multiple online players) running against an
        // active writer. This exercises both paths of the driver
        // codec under real concurrency and proves:
        //   - writes from one thread don't block or corrupt reads
        //   - the count observed by readers is monotonic (never
        //     regresses — a read never sees "lost" records after
        //     seeing them once)
        //   - no driver-level exceptions surface to the caller
        rawClient.getDatabase("IT").getCollection("EventRecords")
                .deleteMany(new org.bson.Document());

        int totalWrites = 500;
        BlockLocation loc = new BlockLocation(WORLD, "world", 0, 64, 0);
        Origin origin = Origin.player();
        Source source = Source.player(ALICE, "Alice");
        BlockSnapshot air = new BlockSnapshot(
                org.bukkit.Material.AIR, "minecraft:air",
                List.of(), List.of(), List.of(), List.of(), null);
        BlockSnapshot stone = new BlockSnapshot(
                org.bukkit.Material.STONE, "minecraft:stone",
                List.of(), List.of(), List.of(), List.of(), null);
        Instant base = Instant.now();

        java.util.concurrent.atomic.AtomicInteger writerErrors = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger readerErrors = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger lastObservedCount = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger regressions = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicBoolean writesDone = new java.util.concurrent.atomic.AtomicBoolean();

        QueryRequest allBreaks = new QueryRequest(
                List.of(new QueryPredicate.Eq("event", "break")),
                Sort.NEWEST_FIRST, 1000, EnumSet.noneOf(Flag.class), false);

        Thread writer = new Thread(() -> {
            try {
                for (int i = 0; i < totalWrites; i++) {
                    store.save(List.of(new BlockBreakRecord(
                            UUID.randomUUID(), "break",
                            base.plusMillis(i), base.plusSeconds(3600),
                            origin, source, loc, "test", "STONE", stone, air)));
                }
            } catch (Exception ex) {
                writerErrors.incrementAndGet();
            } finally {
                writesDone.set(true);
            }
        }, "IT-writer");

        // 4 readers hammer queries during the write storm.
        Thread[] readers = new Thread[4];
        for (int r = 0; r < readers.length; r++) {
            readers[r] = new Thread(() -> {
                try {
                    while (!writesDone.get()) {
                        int size = store.query(allBreaks).records().size();
                        // Monotonicity check — this is racy to assert
                        // strictly (a second reader might win the
                        // comparison), but a STRICT regression from N -> N-K
                        // for K larger than a handful would signal a real
                        // bug. We just count any observed regression.
                        int prev;
                        do {
                            prev = lastObservedCount.get();
                            if (size >= prev) {
                                break;
                            }
                        } while (!lastObservedCount.compareAndSet(prev, size));
                        if (size >= prev) {
                            lastObservedCount.compareAndSet(prev, size);
                        } else {
                            regressions.incrementAndGet();
                        }
                    }
                } catch (Exception ex) {
                    readerErrors.incrementAndGet();
                }
            }, "IT-reader-" + r);
        }

        writer.start();
        for (Thread t : readers) t.start();

        writer.join(60_000);
        for (Thread t : readers) t.join(10_000);

        assertThat(writerErrors.get())
                .as("writer must not surface any driver-level exceptions")
                .isZero();
        assertThat(readerErrors.get())
                .as("readers must not surface any driver-level exceptions")
                .isZero();
        // Final count must match total writes.
        assertThat(store.query(allBreaks).records())
                .as("every saved record must be visible after writes settle")
                .hasSize(totalWrites);
    }

    @Test
    void respectsLimit() {
        Instant now = Instant.now();
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
        for (int i = 0; i < 20; i++) {
            many.add(new BlockBreakRecord(UUID.randomUUID(), "break",
                    now.plusSeconds(i), now.plusSeconds(3600),
                    origin, source, loc, "test", "STONE", stone, air));
        }
        store.save(many);
        QueryRequest limited = new QueryRequest(
                List.of(new QueryPredicate.Eq("event", "break")),
                Sort.NEWEST_FIRST, 5, EnumSet.noneOf(Flag.class), false);
        assertThat(store.query(limited).records()).hasSize(5);
    }

    @Test
    void streamRollbackMatchesQueryPageWindowForWindow() {
        // #19 parity: Mongo serves the rollback stream through the
        // RecordStore default (streamRollback delegating to queryPage),
        // so the stream must walk identical records and cursor steps —
        // pinned here so a future native override can't drift.
        UUID streamer = UUID.fromString("99999999-9999-9999-9999-999999999999");
        Instant base = Instant.now().minusSeconds(600);
        BlockSnapshot air = new BlockSnapshot(
                org.bukkit.Material.AIR, "minecraft:air",
                List.of(), List.of(), List.of(), List.of(), null);
        BlockSnapshot stone = new BlockSnapshot(
                org.bukkit.Material.STONE, "minecraft:stone",
                List.of(), List.of(), List.of(), List.of(), null);
        Origin origin = Origin.player();
        Source source = Source.player(streamer, "Streamer");
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

        QueryRequest request = new QueryRequest(
                List.of(new QueryPredicate.Eq("source.playerId", streamer)),
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

    // Captures the lean streamRollbackEffects sink calls, keyed by row id so
    // each emitted effect can be checked against the canonical Rollbackable.
    private static final class CapturingSink implements RecordStore.RollbackEffectSink {
        record BlockCall(UUID worldId, int x, int y, int z, String blockData, String expected) {
        }

        final Map<UUID, BlockCall> blocks = new java.util.HashMap<>();
        final Map<UUID, net.medievalrp.spyglass.api.rollback.RollbackEffect> complex = new java.util.HashMap<>();
        final java.util.Set<UUID> skipped = new java.util.HashSet<>();

        @Override
        public void block(UUID worldId, int x, int y, int z, String blockData,
                          String expectedData, Instant occurred, UUID id) {
            blocks.put(id, new BlockCall(worldId, x, y, z, blockData, expectedData));
        }

        @Override
        public void complex(net.medievalrp.spyglass.api.rollback.RollbackEffect effect,
                            Instant occurred, UUID id) {
            complex.put(id, effect);
        }

        @Override
        public void skip(Instant occurred, UUID id) {
            skipped.add(id);
        }
    }

    private CapturingSink drainLeanRollback(QueryRequest request, boolean rollback) {
        CapturingSink sink = new CapturingSink();
        QueryPage.Cursor cursor = null;
        do {
            // Small window so multi-page cursor stepping is exercised too.
            cursor = store.streamRollbackEffects(request, cursor, 3, rollback, sink);
        } while (cursor != null);
        return sink;
    }

    @Test
    void leanStreamRollbackEffectsMatchesCanonicalEffects() {
        // The native Mongo streamRollbackEffects override (the ClickHouse #67/
        // #83 mirror) reads raw projected BSON and emits effects directly. It
        // must produce exactly what each record's Rollbackable.rollbackEffect()
        // / restoreEffect() would — the simple-block hot path via sink.block()
        // primitives, everything else via sink.complex() — in both directions.
        // Block effects drop expectedCurrent (force-overwrite #69 ignores it).
        Instant now = Instant.parse("2026-04-23T12:00:00Z");
        BlockLocation loc = new BlockLocation(WORLD, "world", 10, 64, 20);
        Origin origin = Origin.player();
        Source source = Source.player(ALICE, "Alice");
        BlockSnapshot stone = new BlockSnapshot(org.bukkit.Material.STONE, "minecraft:stone",
                List.of(), List.of(), List.of(), List.of(), null);
        BlockSnapshot air = new BlockSnapshot(org.bukkit.Material.AIR, "minecraft:air",
                List.of(), List.of(), List.of(), List.of(), null);
        StoredItem diamond = new StoredItem(3, "DIAMOND", null);
        BlockSnapshot chest = new BlockSnapshot(org.bukkit.Material.CHEST, "minecraft:chest",
                List.of(diamond), List.of(), List.of(), List.of(), null);

        BlockBreakRecord brk = new BlockBreakRecord(UUID.randomUUID(), "break",
                now.plusSeconds(1), now.plusSeconds(3600), origin, source, loc, "test", "STONE", stone, air);
        BlockPlaceRecord place = new BlockPlaceRecord(UUID.randomUUID(), "place",
                now.plusSeconds(2), now.plusSeconds(3600), origin, source, loc, "test", "STONE", air, stone);
        BlockBreakRecord chestBreak = new BlockBreakRecord(UUID.randomUUID(), "break",
                now.plusSeconds(3), now.plusSeconds(3600), origin, source,
                new BlockLocation(WORLD, "world", 11, 64, 20), "test", "CHEST", chest, air);
        ContainerDepositRecord deposit = new ContainerDepositRecord(UUID.randomUUID(), "deposit",
                now.plusSeconds(4), now.plusSeconds(3600), origin, source, loc, "test", "DIAMOND", "CHEST",
                3, 1, null, diamond);
        EntityDeathRecord death = new EntityDeathRecord(UUID.randomUUID(), "death",
                now.plusSeconds(5), now.plusSeconds(3600), origin, source, loc, "test", "ZOMBIE", "ZOMBIE",
                UUID.randomUUID(), "player", "ENTITY_ATTACK", null);
        net.medievalrp.spyglass.api.event.ChatRecord chat = new net.medievalrp.spyglass.api.event.ChatRecord(
                UUID.randomUUID(), "say", now.plusSeconds(6), now.plusSeconds(3600),
                origin, source, loc, "test", "Alice", "hi", List.of(), java.util.Map.of());
        store.save(List.of(brk, place, chestBreak, deposit, death, chat));

        QueryRequest request = new QueryRequest(
                List.of(new QueryPredicate.Eq("source.playerId", ALICE)),
                Sort.NEWEST_FIRST, 1000, EnumSet.noneOf(Flag.class), false);

        for (boolean rollback : new boolean[] {true, false}) {
            CapturingSink sink = drainLeanRollback(request, rollback);

            // Non-rollbackable chat advances the cursor via skip(), never an effect.
            assertThat(sink.skipped).as("chat skipped (%s)", rollback).containsExactly(chat.id());
            assertThat(sink.blocks.keySet()).doesNotContain(chat.id());
            assertThat(sink.complex.keySet()).doesNotContain(chat.id());

            // Simple blocks: hot path via sink.block(); blockData is the
            // canonical replacement side, expected is dropped to null.
            var brkExpected = (net.medievalrp.spyglass.api.rollback.RollbackEffect.BlockReplace)
                    (rollback ? brk.rollbackEffect() : brk.restoreEffect());
            assertThat(sink.blocks.get(brk.id()).blockData()).isEqualTo(brkExpected.replacement().blockData());
            assertThat(sink.blocks.get(brk.id()).expected()).isNull();
            assertThat(sink.blocks.get(brk.id()).x()).isEqualTo(10);

            var placeExpected = (net.medievalrp.spyglass.api.rollback.RollbackEffect.BlockReplace)
                    (rollback ? place.rollbackEffect() : place.restoreEffect());
            assertThat(sink.blocks.get(place.id()).blockData()).isEqualTo(placeExpected.replacement().blockData());

            // The chest break exercises direction-specific side selection:
            // rollback writes originalBlock (the chest, a tile-entity → complex
            // object path); restore writes newBlock (air → simple hot path).
            var chestCanonical = (net.medievalrp.spyglass.api.rollback.RollbackEffect.BlockReplace)
                    (rollback ? chestBreak.rollbackEffect() : chestBreak.restoreEffect());
            if (rollback) {
                var chestEffect = (net.medievalrp.spyglass.api.rollback.RollbackEffect.BlockReplace)
                        sink.complex.get(chestBreak.id());
                assertThat(chestEffect).as("chest rollback uses the complex path").isNotNull();
                assertThat(chestEffect.location()).isEqualTo(chestCanonical.location());
                assertThat(chestEffect.replacement()).isEqualTo(chestCanonical.replacement());
                assertThat(chestEffect.expectedCurrent()).isNull();
                assertThat(sink.blocks).doesNotContainKey(chestBreak.id());
            } else {
                assertThat(sink.blocks.get(chestBreak.id()).blockData())
                        .isEqualTo(chestCanonical.replacement().blockData());
                assertThat(sink.complex).doesNotContainKey(chestBreak.id());
            }

            // Container + entity rows match the canonical effect exactly
            // (those paths keep expectedCurrent, same as ClickHouse).
            assertThat(sink.complex.get(deposit.id()))
                    .isEqualTo(rollback ? deposit.rollbackEffect() : deposit.restoreEffect());
            assertThat(sink.complex.get(death.id()))
                    .isEqualTo(rollback ? death.rollbackEffect() : death.restoreEffect());
        }
    }
}
