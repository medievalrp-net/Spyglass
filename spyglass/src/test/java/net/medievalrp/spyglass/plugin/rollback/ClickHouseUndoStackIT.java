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
 * Streaming undo ledger against a real ClickHouse: chunked capture via
 * the withheld-head seal protocol, paged replay, crash invisibility,
 * and pre-streaming (legacy) row compatibility. Each test uses its own
 * player id, so no cross-test wipe is needed.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ClickHouseUndoStackIT {

    private static final UUID WORLD = UUID.fromString("77777777-7777-7777-7777-777777777777");
    private static final int CHUNK = 3;

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
        stack = new ClickHouseUndoStack(store.client(), "spyglass_it", CHUNK);
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
            // Two appends spanning chunk boundaries: 8 effects at
            // chunk size 3 → chunks of 3 / 3 / 2.
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
        // No seal — simulates a crash/failure mid-capture.
        writer.close();
        assertThat(stack.openLatest(player))
                .as("op without a sealed head chunk must not be poppable")
                .isEmpty();
    }

    @Test
    void preStreamingRowsRemainReadable() throws Exception {
        // Rows written by the pre-streaming code: every chunk carries
        // the real chunk_count (no withheld head). The sealed-head
        // predicate must accept them unchanged.
        UUID player = UUID.randomUUID();
        UUID op = UUID.randomUUID();
        String c0 = BsonBlobs.encodeRollbackEffectsBase64(List.of(effectAt(0), effectAt(1)));
        String c1 = BsonBlobs.encodeRollbackEffectsBase64(List.of(effectAt(2)));
        String insert = "INSERT INTO `spyglass_it`.`undo_history` "
                + "(operation_id, chunk_index, chunk_count, player_id, created_at, "
                + "operation_type, inverse_effects, deleted) VALUES "
                + "(toUUID('" + op + "'), 0, 2, toUUID('" + player + "'), "
                + "'2026-06-10 00:00:00.000', 'ROLLBACK', '" + c0 + "', 0), "
                + "(toUUID('" + op + "'), 1, 2, toUUID('" + player + "'), "
                + "'2026-06-10 00:00:00.000', 'ROLLBACK', '" + c1 + "', 0)";
        store.client().execute(insert).get(30, TimeUnit.SECONDS).close();

        Optional<UndoStack.UndoReader> opened = stack.openLatest(player);
        assertThat(opened).isPresent();
        try (UndoStack.UndoReader reader = opened.get()) {
            assertThat(reader.chunkCount()).isEqualTo(2);
            List<RollbackEffect> all = new ArrayList<>();
            for (Optional<List<RollbackEffect>> chunk = reader.nextChunk();
                    chunk.isPresent(); chunk = reader.nextChunk()) {
                all.addAll(chunk.get());
            }
            assertThat(xs(all)).containsExactly(0, 1, 2);
            reader.tombstone();
        }
        assertThat(stack.openLatest(player)).isEmpty();
    }

    @Test
    void popReturnsNewestOperationFirst() throws Exception {
        UUID player = UUID.randomUUID();
        stack.push(player, "ROLLBACK", List.of(effectAt(10)));
        Thread.sleep(10); // created_at has ms precision; avoid a tie
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
    void missingChunkThrowsInsteadOfSilentlyShortApplying() throws Exception {
        UUID player = UUID.randomUUID();
        try (UndoStack.UndoWriter writer = stack.beginPush(player, "ROLLBACK")) {
            for (int i = 0; i < 8; i++) {
                writer.append(List.of(effectAt(i)));
            }
            writer.seal();
        }
        // Tombstone chunk 1 behind the reader's back — a hole.
        Optional<UndoStack.UndoReader> peek = stack.openLatest(player);
        assertThat(peek).isPresent();
        UUID op = peek.get().operationId();
        peek.get().close();
        String holePunch = "INSERT INTO `spyglass_it`.`undo_history` "
                + "(operation_id, chunk_index, chunk_count, player_id, created_at, "
                + "operation_type, inverse_effects, deleted) "
                + "SELECT operation_id, chunk_index, chunk_count, player_id, created_at, "
                + "operation_type, '', 1 FROM `spyglass_it`.`undo_history` "
                + "WHERE operation_id = toUUID('" + op + "') AND chunk_index = 1";
        store.client().execute(holePunch).get(30, TimeUnit.SECONDS).close();

        try (UndoStack.UndoReader reader = stack.openLatest(player).orElseThrow()) {
            assertThat(reader.nextChunk()).isPresent(); // chunk 0 cached from the head row
            assertThatThrownBy(reader::nextChunk)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("missing chunk 1");
        }
    }
}
