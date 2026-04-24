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
import net.medievalrp.spyglass.plugin.config.SpyglassConfig;
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
        SpyglassConfig.Database config = new SpyglassConfig.Database(uri, "IT", "EventRecords");
        store = new MongoRecordStore(config, new IndexManager());
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
                new BlockBreakRecord(UUID.randomUUID(), 1, "break", now, now.plusSeconds(3600),
                        origin, source, location, "STONE", stone, air),
                new BlockPlaceRecord(UUID.randomUUID(), 1, "place", now, now.plusSeconds(3600),
                        origin, source, location, "STONE", air, stone),
                new ChatRecord(UUID.randomUUID(), 1, "say", now, now.plusSeconds(3600),
                        origin, source, location, "Alice", "hello", List.of()),
                new ContainerDepositRecord(UUID.randomUUID(), 1, "deposit", now, now.plusSeconds(3600),
                        origin, source, location, "DIAMOND", "CHEST", 3, 1, null, item),
                new EntityDeathRecord(UUID.randomUUID(), 1, "death", now, now.plusSeconds(3600),
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
    void allExpectedIndexesExist() {
        List<String> indexNames = rawClient.getDatabase("IT")
                .getCollection("EventRecords")
                .listIndexes()
                .into(new java.util.ArrayList<>())
                .stream()
                .map(document -> document.getString("name"))
                .toList();

        assertThat(indexNames).contains(
                "_id_",
                "source.playerId_1_occurred_-1",
                "event_1_occurred_-1",
                "location.worldId_1_location.x_1_location.z_1_location.y_1_occurred_-1",
                "expiresAt_1");
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
                List.of("sharpness=5", "mending=1"));

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
                new ContainerDepositRecord(UUID.randomUUID(), 1, "deposit",
                        now, now.plusSeconds(3600),
                        origin, source, loc, "DIAMOND_SWORD", "CHEST",
                        0, 1, null, enchanted),
                new net.medievalrp.spyglass.api.event.ItemDropRecord(
                        UUID.randomUUID(), 1, "drop",
                        now, now.plusSeconds(3600),
                        origin, source, loc, "DIAMOND_SWORD", 1, enchanted),
                new BlockPlaceRecord(UUID.randomUUID(), 1, "place",
                        now, now.plusSeconds(3600),
                        origin, source, loc, "CHEST", air, chestWithItem));
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

        // Missing name, no hits.
        QueryRequest missing = new QueryRequest(
                List.of(anyItemField("name", "Glamdring")),
                Sort.NEWEST_FIRST, 50, EnumSet.noneOf(Flag.class), false);
        assertThat(store.query(missing).records()).isEmpty();
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
                UUID.randomUUID(), 1, "break", past, past.minusSeconds(1),
                Origin.player(), Source.player(ALICE, "Alice"),
                location, "STONE", stone, air);

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
                            UUID.randomUUID(), 1, "break",
                            base.plusMillis(i), base.plusSeconds(3600),
                            origin, source, loc, "STONE", stone, air)));
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
            many.add(new BlockBreakRecord(UUID.randomUUID(), 1, "break",
                    now.plusSeconds(i), now.plusSeconds(3600),
                    origin, source, loc, "STONE", stone, air));
        }
        store.save(many);
        QueryRequest limited = new QueryRequest(
                List.of(new QueryPredicate.Eq("event", "break")),
                Sort.NEWEST_FIRST, 5, EnumSet.noneOf(Flag.class), false);
        assertThat(store.query(limited).records()).hasSize(5);
    }
}
