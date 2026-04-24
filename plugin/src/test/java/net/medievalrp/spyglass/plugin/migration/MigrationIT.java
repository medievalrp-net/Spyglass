package net.medievalrp.spyglass.plugin.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import net.medievalrp.spyglass.api.event.StoredItem;
import net.medievalrp.spyglass.api.util.Duration;
import net.medievalrp.spyglass.plugin.config.SpyglassConfig;
import net.medievalrp.spyglass.plugin.storage.IndexManager;
import net.medievalrp.spyglass.plugin.storage.MongoRecordStore;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MongoDBContainer;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MigrationIT {

    private static final UUID ALICE = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID WORLD = UUID.fromString("77777777-7777-7777-7777-777777777777");

    private MongoDBContainer container;
    private MongoClient client;
    private MongoRecordStore recordStore;
    private SpyglassConfig config;

    @BeforeAll
    void setup() {
        assumeThat(DockerClientFactory.instance().isDockerAvailable())
                .as("docker not available")
                .isTrue();
        container = new MongoDBContainer("mongo:7.0");
        container.start();
        String uri = container.getReplicaSetUrl();
        client = MongoClients.create(uri);
        config = new SpyglassConfig(
                new SpyglassConfig.Database(uri, "Spyglass", "EventRecords"),
                new SpyglassConfig.Storage(Duration.parse("4w"), 1000, Duration.parse("5s")),
                new SpyglassConfig.Defaults(true, 5, Duration.parse("3d")),
                new SpyglassConfig.Limits(250, 1000, 10000, 50),
                Map.of(),
                new SpyglassConfig.Tool(null));
        recordStore = new MongoRecordStore(config.database(), new IndexManager());
    }

    @AfterAll
    void teardown() {
        if (recordStore != null) {
            recordStore.close();
        }
        if (client != null) {
            client.close();
        }
        if (container != null) {
            container.stop();
        }
    }

    @Test
    void migratesSampleV1Corpus() {
        MongoDatabase v1Db = client.getDatabase("v1");
        MongoCollection<Document> dataEntry = v1Db.getCollection("DataEntry");
        dataEntry.drop();
        dataEntry.insertMany(java.util.List.of(
                breakDoc(10), breakDoc(11), placeDoc(12),
                sayDoc(), joinDoc(), deferredDoc("bookshelf-insert"),
                deferredDoc("vault")));

        V1ToV2Translator translator = new V1ToV2Translator(
                stubDecoder(), WorldNameLookup.usingUuid(), Logger.getLogger(MigrationIT.class.getName()));
        MigrationService service = new MigrationService(recordStore, config, translator,
                Logger.getLogger(MigrationIT.class.getName()));

        AtomicLong lastProcessed = new AtomicLong();
        MigrationService.MigrationReport report = service.migrate(
                MigrationService.MigrationOptions.defaults(),
                update -> lastProcessed.set(update.processed()));

        assertThat(report.processed()).isEqualTo(7);
        assertThat(report.translated()).isEqualTo(5);
        assertThat(report.skippedDeferred()).isEqualTo(2);
        assertThat(report.failed()).isEqualTo(0);

        long written = client.getDatabase("Spyglass")
                .getCollection("EventRecords")
                .countDocuments();
        assertThat(written).isEqualTo(5);
    }

    @Test
    void dryRunProducesNoWrites() {
        MongoDatabase v1Db = client.getDatabase("v1");
        MongoCollection<Document> dataEntry = v1Db.getCollection("DataEntry");
        dataEntry.drop();
        dataEntry.insertMany(java.util.List.of(breakDoc(20), placeDoc(21)));

        client.getDatabase("Spyglass").getCollection("EventRecords").drop();
        recordStore.close();
        recordStore = new MongoRecordStore(config.database(), new IndexManager());

        V1ToV2Translator translator = new V1ToV2Translator(
                stubDecoder(), WorldNameLookup.usingUuid(), Logger.getLogger(MigrationIT.class.getName()));
        MigrationService service = new MigrationService(recordStore, config, translator,
                Logger.getLogger(MigrationIT.class.getName()));

        MigrationService.MigrationReport report = service.migrate(
                MigrationService.MigrationOptions.defaults().withDryRun(true),
                update -> {
                });

        assertThat(report.translated()).isEqualTo(2);
        long written = client.getDatabase("Spyglass")
                .getCollection("EventRecords")
                .countDocuments();
        assertThat(written).isEqualTo(0);
    }

    private V1ItemDecoder stubDecoder() {
        return new V1ItemDecoder() {
            @Override
            public Optional<StoredItem> decode(int slot, Map<String, Object> itemDoc) {
                if (itemDoc == null || !itemDoc.containsKey("type")) {
                    return Optional.empty();
                }
                return Optional.of(new StoredItem(slot, String.valueOf(itemDoc.get("type")), null));
            }
        };
    }

    private Document baseDoc(String event) {
        return new Document("Event", event)
                .append("Created", Date.from(Instant.now()))
                .append("Expires", Date.from(Instant.now().plusSeconds(604_800)))
                .append("Player", ALICE.toString())
                .append("Location", new Document("X", 10).append("Y", 64).append("Z", 10)
                        .append("World", WORLD.toString()));
    }

    private Document breakDoc(int x) {
        return baseDoc("break")
                .append("Target", "STONE")
                .append("Location", new Document("X", x).append("Y", 64).append("Z", 10)
                        .append("World", WORLD.toString()))
                .append("OriginalBlock", new Document("MaterialType", "STONE").append("BlockData", "minecraft:stone"))
                .append("NewBlock", new Document("MaterialType", "AIR").append("BlockData", "minecraft:air"));
    }

    private Document placeDoc(int x) {
        return baseDoc("place")
                .append("Target", "GLASS")
                .append("Location", new Document("X", x).append("Y", 64).append("Z", 10)
                        .append("World", WORLD.toString()))
                .append("OriginalBlock", new Document("MaterialType", "AIR").append("BlockData", "minecraft:air"))
                .append("NewBlock", new Document("MaterialType", "GLASS").append("BlockData", "minecraft:glass"));
    }

    private Document sayDoc() {
        return baseDoc("say").append("Message", "hello");
    }

    private Document joinDoc() {
        return baseDoc("join").append("IpAddress", "127.0.0.1");
    }

    private Document deferredDoc(String event) {
        return baseDoc(event).append("Target", "ANY");
    }
}
