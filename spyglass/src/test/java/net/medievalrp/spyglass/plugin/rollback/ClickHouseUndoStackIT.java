package net.medievalrp.spyglass.plugin.rollback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.medievalrp.spyglass.api.event.BlockSnapshot;
import net.medievalrp.spyglass.api.rollback.RollbackEffect;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.plugin.storage.BsonBlobs;
import net.medievalrp.spyglass.plugin.storage.ClickHouseRecordStore;
import org.bukkit.Material;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.clickhouse.ClickHouseContainer;

/**
 * Undo ledger against a real ClickHouse: reference operations (the
 * write path) plus read-compatibility with rows from older builds
 * (chunked and whole-operation, raw-inserted here the way old code
 * wrote them). Each test uses its own player id, so no cross-test
 * wipe is needed.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ClickHouseUndoStackIT {

    private static final UUID WORLD = UUID.fromString("77777777-7777-7777-7777-777777777777");

    private ClickHouseContainer container;
    private ClickHouseRecordStore store;
    private ClickHouseUndoStack stack;

    @BeforeAll
    void setup() {
        assumeThat(DockerClientFactory.instance().isDockerAvailable())
                .as("docker not available")
                .isTrue();
        container = new ClickHouseContainer("clickhouse/clickhouse-server:24.8-alpine");
        container.start();
        // The record store bootstraps the schema, including undo_history.
        store = new ClickHouseRecordStore(
                container.getHost(),
                container.getMappedPort(8123),
                "spyglass_it",
                "event_records_it",
                container.getUsername(),
                container.getPassword(),
                false);
        stack = new ClickHouseUndoStack(store.client(), "spyglass_it");
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

    private static RollbackEffect effectAt(int x) {
        BlockSnapshot stone = new BlockSnapshot(Material.STONE, "minecraft:stone",
                List.of(), List.of(), List.of(), List.of(), null);
        BlockSnapshot air = new BlockSnapshot(Material.AIR, "minecraft:air",
                List.of(), List.of(), List.of(), List.of(), null);
        return new RollbackEffect.BlockReplace(
                new BlockLocation(WORLD, "world", x, 64, 0), stone, air);
    }

    // undo_history has a 1-day delete TTL and ClickHouse drops expired
    // rows as early as insert-part formation — a hardcoded created_at
    // here was a time bomb that started failing the suite the moment
    // UTC crossed midnight past the literal's day. Stay relative to the
    // wall clock, shared across rows so chunks of one operation keep
    // the legacy writer's single-timestamp shape.
    private static final String LEGACY_CREATED_AT = java.time.format.DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
            .withZone(java.time.ZoneOffset.UTC)
            .format(java.time.Instant.now().minusSeconds(300));

    private void insertLegacyRow(UUID op, UUID player, int chunkIndex, int chunkCount,
                                 String payloadBase64) throws Exception {
        String insert = "INSERT INTO `spyglass_it`.`undo_history` "
                + "(operation_id, chunk_index, chunk_count, player_id, created_at, "
                + "operation_type, inverse_effects, deleted) VALUES "
                + "(toUUID('" + op + "'), " + chunkIndex + ", " + chunkCount + ", "
                + "toUUID('" + player + "'), '" + LEGACY_CREATED_AT + "', 'ROLLBACK', '"
                + payloadBase64 + "', 0)";
        store.client().execute(insert).get(30, TimeUnit.SECONDS).close();
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
        Thread.sleep(10); // created_at has ms precision; avoid a tie
        stack.pushReference(player, "RESTORE", "newer");

        UndoStack.ReplayReference first =
                (UndoStack.ReplayReference) stack.openLatest(player).orElseThrow();
        assertThat(first.operationType()).isEqualTo("RESTORE");
        assertThat(first.referenceBase64()).isEqualTo("newer");
        first.tombstone();

        UndoStack.ReplayReference second =
                (UndoStack.ReplayReference) stack.openLatest(player).orElseThrow();
        assertThat(second.operationType()).isEqualTo("ROLLBACK");
        assertThat(second.referenceBase64()).isEqualTo("older");
        second.tombstone();

        assertThat(stack.openLatest(player)).isEmpty();
    }

    @Test
    void legacyChunkedRowsRemainReadableInOrder() throws Exception {
        // Rows as the pre-reference chunked code wrote them: every row
        // carries the real chunk_count.
        UUID player = UUID.randomUUID();
        UUID op = UUID.randomUUID();
        insertLegacyRow(op, player, 0, 2,
                BsonBlobs.encodeRollbackEffectsBase64(List.of(effectAt(0), effectAt(1))));
        insertLegacyRow(op, player, 1, 2,
                BsonBlobs.encodeRollbackEffectsBase64(List.of(effectAt(2))));

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
    void missingLegacyChunkThrowsInsteadOfSilentlyShortApplying() throws Exception {
        UUID player = UUID.randomUUID();
        UUID op = UUID.randomUUID();
        // Chunk 1 of 3 never written — a hole.
        insertLegacyRow(op, player, 0, 3,
                BsonBlobs.encodeRollbackEffectsBase64(List.of(effectAt(0))));
        insertLegacyRow(op, player, 2, 3,
                BsonBlobs.encodeRollbackEffectsBase64(List.of(effectAt(2))));

        UndoStack.LegacyOperation legacy =
                (UndoStack.LegacyOperation) stack.openLatest(player).orElseThrow();
        assertThat(legacy.nextChunk()).isPresent(); // chunk 0 cached from the head row
        assertThatThrownBy(legacy::nextChunk)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing chunk 1");
        legacy.close();
    }
}
