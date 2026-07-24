package net.medievalrp.spyglass.plugin.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assumptions.assumeThat;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.medievalrp.spyglass.api.event.StoredItem;
import net.medievalrp.spyglass.api.util.EventIds;
import net.medievalrp.spyglass.plugin.storage.IndexManager;
import net.medievalrp.spyglass.plugin.storage.MongoRecordStore;
import org.bson.Document;
import org.bson.types.Binary;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MongoDBContainer;

/**
 * {@link MongoPlayerSnapshotStore} against a real Mongo: save/read roundtrip
 * with blob hydration, latest-at-or-before selection, intern dedupe asserted
 * with the driver over the intern collection, double-intern no-throw, and
 * prune's orphan GC (unreferenced payloads dropped, shared ones kept). Each
 * test uses its own player / unique payloads, so no cross-test wipe is needed.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MongoPlayerSnapshotStoreIT {

    private MongoDBContainer container;
    private MongoClient rawClient;
    private MongoRecordStore store;
    private MongoPlayerSnapshotStore snapStore;

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
        snapStore = new MongoPlayerSnapshotStore(store.database(), store.codecRegistry());
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

    // --- helpers ---------------------------------------------------

    private MongoCollection<Document> itemsCollection() {
        return store.database().getCollection("snapshot_items");
    }

    private MongoCollection<Document> snapshotsCollection() {
        return store.database().getCollection("player_snapshots");
    }

    private static byte[] payload(String seed) {
        return (seed + ":" + UUID.randomUUID()).getBytes();
    }

    private static StoredItem item(int slot, String material, byte[] raw) {
        return new StoredItem(slot, material, Base64.getEncoder().encodeToString(raw));
    }

    private static PlayerSnapshot snapshot(UUID player, Instant at, long contentHash,
                                           List<SnapshotSlot> slots) {
        return new PlayerSnapshot(EventIds.newId(), player, "Tester", at,
                PlayerSnapshot.CAUSE_SWEEP, contentHash, slots);
    }

    private static Binary hash(byte[] raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw);
            return new Binary(Arrays.copyOf(digest, 16));
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private long internCount(byte[] raw) {
        return itemsCollection().countDocuments(Filters.eq("_id", hash(raw)));
    }

    // --- tests -----------------------------------------------------

    @Test
    void saveAndReadRoundtrip() {
        UUID player = UUID.randomUUID();
        byte[] sword = payload("sword");
        byte[] cobble = payload("cobble");
        Instant at = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        snapStore.save(snapshot(player, at, 0xABCDEF12L, List.of(
                new SnapshotSlot(0, 1, item(0, "minecraft:diamond_sword", sword)),
                new SnapshotSlot(9, 32, item(9, "minecraft:cobblestone", cobble)))));

        Optional<PlayerSnapshot> read = snapStore.latestAtOrBefore(player, at.plusSeconds(1));
        assertThat(read).isPresent();
        PlayerSnapshot snap = read.get();
        assertThat(snap.player()).isEqualTo(player);
        assertThat(snap.cause()).isEqualTo(PlayerSnapshot.CAUSE_SWEEP);
        assertThat(snap.contentHash()).isEqualTo(0xABCDEF12L);
        assertThat(snap.slots()).hasSize(2);

        SnapshotSlot s0 = snap.slots().stream().filter(s -> s.slot() == 0).findFirst().orElseThrow();
        assertThat(s0.count()).isEqualTo(1);
        assertThat(s0.item().material()).isEqualTo("minecraft:diamond_sword");
        assertThat(s0.item().data()).isEqualTo(Base64.getEncoder().encodeToString(sword));

        SnapshotSlot s9 = snap.slots().stream().filter(s -> s.slot() == 9).findFirst().orElseThrow();
        assertThat(s9.count()).isEqualTo(32);
        assertThat(s9.item().material()).isEqualTo("minecraft:cobblestone");
        assertThat(s9.item().data()).isEqualTo(Base64.getEncoder().encodeToString(cobble));

        assertThat(snapStore.lastContentHash(player)).hasValue(0xABCDEF12L);
    }

    @Test
    void latestAtOrBeforeSelectsNewest() {
        UUID player = UUID.randomUUID();
        Instant t1 = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MILLIS);
        Instant t2 = Instant.now().minusSeconds(10).truncatedTo(ChronoUnit.MILLIS);
        snapStore.save(snapshot(player, t1, 111L, List.of(
                new SnapshotSlot(0, 1, item(0, "minecraft:stone", payload("s1"))))));
        snapStore.save(snapshot(player, t2, 222L, List.of(
                new SnapshotSlot(0, 1, item(0, "minecraft:dirt", payload("s2"))))));

        assertThat(snapStore.latestAtOrBefore(player, t2.plusSeconds(5)).orElseThrow().contentHash())
                .isEqualTo(222L);
        assertThat(snapStore.latestAtOrBefore(player, t2.minusSeconds(5)).orElseThrow().contentHash())
                .isEqualTo(111L);
        assertThat(snapStore.latestAtOrBefore(player, t1.minusSeconds(5))).isEmpty();
    }

    @Test
    void internDedupesAcrossSnapshotsAndDoubleInternDoesNotThrow() {
        byte[] shared = payload("shared-kit");
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();

        assertThatCode(() -> {
            snapStore.save(snapshot(a, Instant.now().truncatedTo(ChronoUnit.MILLIS), 1L, List.of(
                    new SnapshotSlot(0, 1, item(0, "minecraft:shield", shared)))));
            // Second snapshot re-interns the identical payload: must not throw.
            snapStore.save(snapshot(b, Instant.now().truncatedTo(ChronoUnit.MILLIS), 2L, List.of(
                    new SnapshotSlot(0, 1, item(0, "minecraft:shield", shared)))));
        }).doesNotThrowAnyException();

        assertThat(internCount(shared)).as("payload interned exactly once").isEqualTo(1L);
    }

    @Test
    void pruneOrphanGcsUnreferencedPayloadsButKeepsShared() {
        UUID oldPlayer = UUID.randomUUID();
        UUID newPlayer = UUID.randomUUID();
        byte[] orphan = payload("orphan");   // only the old snapshot references it
        byte[] shared = payload("shared");   // both snapshots reference it
        byte[] kept = payload("kept");       // only the new snapshot references it
        Instant old = Instant.now().minus(40, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS);
        Instant fresh = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        snapStore.save(snapshot(oldPlayer, old, 1L, List.of(
                new SnapshotSlot(0, 1, item(0, "minecraft:stone", orphan)),
                new SnapshotSlot(1, 1, item(1, "minecraft:shield", shared)))));
        snapStore.save(snapshot(newPlayer, fresh, 2L, List.of(
                new SnapshotSlot(0, 1, item(0, "minecraft:dirt", kept)),
                new SnapshotSlot(1, 1, item(1, "minecraft:shield", shared)))));

        int removed = snapStore.prune(Instant.now().minus(30, ChronoUnit.DAYS));

        assertThat(removed).isEqualTo(1);
        assertThat(snapshotsCollection().countDocuments(Filters.eq("player", oldPlayer)))
                .as("old snapshot doc gone").isZero();
        assertThat(snapStore.latestAtOrBefore(newPlayer, fresh)).isPresent();
        assertThat(internCount(orphan)).as("unreferenced payload GC'd").isZero();
        assertThat(internCount(shared)).as("still-referenced payload kept").isEqualTo(1L);
        assertThat(internCount(kept)).as("live snapshot's payload kept").isEqualTo(1L);
    }
}
