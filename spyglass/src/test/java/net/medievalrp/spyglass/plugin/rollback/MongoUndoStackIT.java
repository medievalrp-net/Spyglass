package net.medievalrp.spyglass.plugin.rollback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.medievalrp.spyglass.api.event.BlockSnapshot;
import net.medievalrp.spyglass.api.rollback.RollbackEffect;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.plugin.storage.IndexManager;
import net.medievalrp.spyglass.plugin.storage.MongoRecordStore;
import org.bukkit.Material;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MongoDBContainer;

/**
 * Undo ledger against a real Mongo: reference operations (the write
 * path) plus read-compatibility with documents from older builds —
 * chunked inverse-effect documents and pre-chunk whole-operation
 * documents, raw-inserted here the way old code wrote them. Each test
 * uses its own player id, so no cross-test wipe is needed.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MongoUndoStackIT {

    private static final UUID WORLD = UUID.fromString("77777777-7777-7777-7777-777777777777");

    private MongoDBContainer container;
    private MongoClient rawClient;
    private MongoRecordStore store;
    private MongoUndoStack stack;

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
        stack = new MongoUndoStack(store.database(), store.codecRegistry());
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

    private static RollbackEffect effectAt(int x) {
        BlockSnapshot stone = new BlockSnapshot(Material.STONE, "minecraft:stone",
                List.of(), List.of(), List.of(), List.of(), null);
        BlockSnapshot air = new BlockSnapshot(Material.AIR, "minecraft:air",
                List.of(), List.of(), List.of(), List.of(), null);
        return new RollbackEffect.BlockReplace(
                new BlockLocation(WORLD, "world", x, 64, 0), stone, air);
    }

    private MongoCollection<MongoUndoStack.UndoChunk> chunkCollection() {
        return store.database().withCodecRegistry(store.codecRegistry())
                .getCollection("UndoHistory", MongoUndoStack.UndoChunk.class);
    }

    @Test
    void referenceRoundTripsAndTombstoneConsumes() {
        UUID player = UUID.randomUUID();
        stack.pushReference(player, "ROLLBACK", "ref-blob-1");

        Optional<UndoStack.Popped> opened = stack.openLatest(player);
        assertThat(opened).isPresent();
        assertThat(opened.get()).isInstanceOf(UndoStack.ReplayReference.class);
        UndoStack.ReplayReference ref = (UndoStack.ReplayReference) opened.get();
        assertThat(ref.operationType()).isEqualTo("ROLLBACK"); // marker stripped
        assertThat(ref.referenceBase64()).isEqualTo("ref-blob-1");
        ref.tombstone();
        ref.close();

        assertThat(stack.openLatest(player)).as("tombstoned ref is gone").isEmpty();
    }

    @Test
    void newestReferenceWins() throws Exception {
        UUID player = UUID.randomUUID();
        stack.pushReference(player, "ROLLBACK", "older");
        Thread.sleep(10);
        stack.pushReference(player, "RESTORE", "newer");

        UndoStack.ReplayReference first =
                (UndoStack.ReplayReference) stack.openLatest(player).orElseThrow();
        assertThat(first.operationType()).isEqualTo("RESTORE");
        assertThat(first.referenceBase64()).isEqualTo("newer");
        first.tombstone();

        UndoStack.ReplayReference second =
                (UndoStack.ReplayReference) stack.openLatest(player).orElseThrow();
        assertThat(second.operationType()).isEqualTo("ROLLBACK");
        second.tombstone();

        assertThat(stack.openLatest(player)).isEmpty();
    }

    @Test
    void legacyChunkedDocumentsRemainReadableInOrder() {
        // Documents as the pre-reference chunked code wrote them: every
        // doc carries the real chunkCount; no reference field semantics.
        UUID player = UUID.randomUUID();
        UUID op = UUID.randomUUID();
        Instant created = Instant.now();
        chunkCollection().insertOne(new MongoUndoStack.UndoChunk(
                UUID.randomUUID(), op, 0, 2, player, created, "ROLLBACK",
                List.of(effectAt(0), effectAt(1)), null));
        chunkCollection().insertOne(new MongoUndoStack.UndoChunk(
                UUID.randomUUID(), op, 1, 2, player, created, "ROLLBACK",
                List.of(effectAt(2)), null));

        Optional<UndoStack.Popped> opened = stack.openLatest(player);
        assertThat(opened).isPresent();
        assertThat(opened.get()).isInstanceOf(UndoStack.LegacyOperation.class);
        UndoStack.LegacyOperation legacy = (UndoStack.LegacyOperation) opened.get();
        assertThat(legacy.chunkCount()).isEqualTo(2);
        List<Integer> xs = new ArrayList<>();
        for (Optional<List<RollbackEffect>> chunk = legacy.nextChunk();
                chunk.isPresent(); chunk = legacy.nextChunk()) {
            for (RollbackEffect e : chunk.get()) {
                xs.add(((RollbackEffect.BlockReplace) e).location().x());
            }
        }
        assertThat(xs).containsExactly(0, 1, 2);
        legacy.tombstone();
        legacy.close();
        assertThat(stack.openLatest(player)).isEmpty();
    }

    @Test
    void legacyWholeOperationDocumentRemainsReadable() {
        // A document written by the pre-chunk code: one whole-op
        // UndoOperation, no chunkIndex field at all.
        UUID player = UUID.randomUUID();
        UndoStack.UndoOperation legacyOp = new UndoStack.UndoOperation(
                UUID.randomUUID(), player, Instant.now(), "ROLLBACK",
                List.of(effectAt(0), effectAt(1)));
        store.database().withCodecRegistry(store.codecRegistry())
                .getCollection("UndoHistory", UndoStack.UndoOperation.class)
                .insertOne(legacyOp);

        Optional<UndoStack.Popped> opened = stack.openLatest(player);
        assertThat(opened).isPresent();
        assertThat(opened.get()).isInstanceOf(UndoStack.LegacyOperation.class);
        UndoStack.LegacyOperation legacy = (UndoStack.LegacyOperation) opened.get();
        assertThat(legacy.chunkCount()).isEqualTo(1);
        Optional<List<RollbackEffect>> chunk = legacy.nextChunk();
        assertThat(chunk).isPresent();
        assertThat(chunk.get()).hasSize(2);
        assertThat(legacy.nextChunk()).isEmpty();
        legacy.tombstone();
        legacy.close();
        assertThat(stack.openLatest(player)).isEmpty();
    }

    @Test
    void missingLegacyChunkThrowsInsteadOfSilentlyShortApplying() {
        UUID player = UUID.randomUUID();
        UUID op = UUID.randomUUID();
        Instant created = Instant.now();
        // Chunk 1 of 3 never written — a hole.
        chunkCollection().insertOne(new MongoUndoStack.UndoChunk(
                UUID.randomUUID(), op, 0, 3, player, created, "ROLLBACK",
                List.of(effectAt(0)), null));
        chunkCollection().insertOne(new MongoUndoStack.UndoChunk(
                UUID.randomUUID(), op, 2, 3, player, created, "ROLLBACK",
                List.of(effectAt(2)), null));

        UndoStack.LegacyOperation legacy =
                (UndoStack.LegacyOperation) stack.openLatest(player).orElseThrow();
        assertThat(legacy.nextChunk()).isPresent(); // chunk 0 from the head doc
        assertThatThrownBy(legacy::nextChunk)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing chunk 1");
        legacy.close();
    }
}
