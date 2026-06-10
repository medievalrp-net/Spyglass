package net.medievalrp.spyglass.plugin.rollback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.model.Filters;
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
 * Streaming undo ledger against a real Mongo: chunked capture via the
 * withheld-head seal protocol, paged replay, crash invisibility, and
 * legacy whole-operation document compatibility. Each test uses its
 * own player id, so no cross-test wipe is needed.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MongoUndoStackIT {

    private static final UUID WORLD = UUID.fromString("77777777-7777-7777-7777-777777777777");
    private static final int CHUNK = 3;

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
        stack = new MongoUndoStack(store.database(), store.codecRegistry(), CHUNK);
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

    private static List<Integer> xs(List<RollbackEffect> effects) {
        List<Integer> out = new ArrayList<>();
        for (RollbackEffect e : effects) {
            out.add(((RollbackEffect.BlockReplace) e).location().x());
        }
        return out;
    }

    @Test
    void streamedCaptureRoundTripsInOrderAcrossChunks() {
        UUID player = UUID.randomUUID();
        try (UndoStack.UndoWriter writer = stack.beginPush(player, "ROLLBACK")) {
            writer.append(List.of(effectAt(0), effectAt(1), effectAt(2), effectAt(3), effectAt(4)));
            writer.append(List.of(effectAt(5), effectAt(6), effectAt(7)));
            assertThat(writer.appended()).isEqualTo(8);
            writer.seal();
        }

        Optional<UndoStack.UndoReader> opened = stack.openLatest(player);
        assertThat(opened).isPresent();
        try (UndoStack.UndoReader reader = opened.get()) {
            assertThat(reader.operationType()).isEqualTo("ROLLBACK");
            assertThat(reader.chunkCount()).isEqualTo(3);
            List<RollbackEffect> all = new ArrayList<>();
            List<Integer> chunkSizes = new ArrayList<>();
            for (Optional<List<RollbackEffect>> chunk = reader.nextChunk();
                    chunk.isPresent(); chunk = reader.nextChunk()) {
                chunkSizes.add(chunk.get().size());
                all.addAll(chunk.get());
            }
            assertThat(chunkSizes).containsExactly(3, 3, 2);
            assertThat(xs(all)).containsExactly(0, 1, 2, 3, 4, 5, 6, 7);
            reader.tombstone();
        }
        assertThat(stack.openLatest(player)).as("tombstoned op is gone").isEmpty();
    }

    @Test
    void unsealedOperationIsInvisible() {
        UUID player = UUID.randomUUID();
        UndoStack.UndoWriter writer = stack.beginPush(player, "ROLLBACK");
        writer.append(List.of(effectAt(0), effectAt(1), effectAt(2), effectAt(3)));
        // No seal — simulates a crash/failure mid-capture; close() abandons.
        writer.close();
        assertThat(stack.openLatest(player))
                .as("op without a sealed head chunk must not be poppable")
                .isEmpty();
    }

    @Test
    void legacyWholeOperationDocumentRemainsReadable() {
        // A document written by the pre-chunk code: one whole-op
        // UndoOperation, no chunkIndex field.
        UUID player = UUID.randomUUID();
        UndoStack.UndoOperation legacyOp = new UndoStack.UndoOperation(
                UUID.randomUUID(), player, Instant.now(), "ROLLBACK",
                List.of(effectAt(0), effectAt(1)));
        store.database().withCodecRegistry(store.codecRegistry())
                .getCollection("UndoHistory", UndoStack.UndoOperation.class)
                .insertOne(legacyOp);

        Optional<UndoStack.UndoReader> opened = stack.openLatest(player);
        assertThat(opened).isPresent();
        try (UndoStack.UndoReader reader = opened.get()) {
            assertThat(reader.chunkCount()).isEqualTo(1);
            Optional<List<RollbackEffect>> chunk = reader.nextChunk();
            assertThat(chunk).isPresent();
            assertThat(xs(chunk.get())).containsExactly(0, 1);
            assertThat(reader.nextChunk()).isEmpty();
            reader.tombstone();
        }
        assertThat(stack.openLatest(player)).isEmpty();
    }

    @Test
    void popReturnsNewestOperationFirst() throws Exception {
        UUID player = UUID.randomUUID();
        stack.push(player, "ROLLBACK", List.of(effectAt(10)));
        Thread.sleep(10);
        stack.push(player, "RESTORE", List.of(effectAt(20)));

        Optional<UndoStack.UndoOperation> first = stack.pop(player);
        assertThat(first).isPresent();
        assertThat(first.get().operationType()).isEqualTo("RESTORE");
        assertThat(xs(first.get().inverseEffects())).containsExactly(20);

        Optional<UndoStack.UndoOperation> second = stack.pop(player);
        assertThat(second).isPresent();
        assertThat(second.get().operationType()).isEqualTo("ROLLBACK");

        assertThat(stack.pop(player)).isEmpty();
    }

    @Test
    void missingChunkThrowsInsteadOfSilentlyShortApplying() {
        UUID player = UUID.randomUUID();
        try (UndoStack.UndoWriter writer = stack.beginPush(player, "ROLLBACK")) {
            for (int i = 0; i < 8; i++) {
                writer.append(List.of(effectAt(i)));
            }
            writer.seal();
        }
        UUID op;
        try (UndoStack.UndoReader peek = stack.openLatest(player).orElseThrow()) {
            op = peek.operationId();
        }
        store.database().withCodecRegistry(store.codecRegistry())
                .getCollection("UndoHistory", MongoUndoStack.UndoChunk.class)
                .deleteOne(Filters.and(
                        Filters.eq("operationId", op),
                        Filters.eq("chunkIndex", 1)));

        try (UndoStack.UndoReader reader = stack.openLatest(player).orElseThrow()) {
            assertThat(reader.nextChunk()).isPresent(); // chunk 0 from the head doc
            assertThatThrownBy(reader::nextChunk)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("missing chunk 1");
        }
    }
}
