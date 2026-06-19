package net.medievalrp.spyglass.plugin.sqlite;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.medievalrp.spyglass.api.event.StoredItem;
import net.medievalrp.spyglass.plugin.command.service.tool.SqliteToolStateStore;
import net.medievalrp.spyglass.plugin.rollback.SqliteUndoStack;
import net.medievalrp.spyglass.plugin.rollback.UndoStack;
import net.medievalrp.spyglass.plugin.salvage.SalvageSnapshot;
import net.medievalrp.spyglass.plugin.salvage.SalvageStore;
import net.medievalrp.spyglass.plugin.salvage.SqliteSalvageStore;
import net.medievalrp.spyglass.plugin.storage.SqliteRecordStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers the three SQLite auxiliary stores (#106) — undo ledger, wand
 * state, salvage — sharing the record store's connection. No Docker.
 */
class SqliteAuxStoresTest {

    @TempDir
    Path dir;
    private SqliteRecordStore store;

    @BeforeEach
    void open() {
        store = new SqliteRecordStore(dir.resolve("spyglass.db"));
    }

    @AfterEach
    void close() {
        store.close();
    }

    @Test
    void undoPushOpenAndTombstone() {
        SqliteUndoStack undo = new SqliteUndoStack(store);
        UUID player = UUID.randomUUID();
        assertThat(undo.openLatest(player)).isEmpty();

        undo.pushReference(player, "ROLLBACK", "ref-blob-base64");
        Optional<UndoStack.Popped> popped = undo.openLatest(player);
        assertThat(popped).isPresent();
        UndoStack.Popped op = popped.get();
        assertThat(op).isInstanceOf(UndoStack.ReplayReference.class);
        assertThat(op.operationType()).isEqualTo("ROLLBACK"); // marker stripped
        assertThat(((UndoStack.ReplayReference) op).referenceBase64()).isEqualTo("ref-blob-base64");

        op.tombstone();
        assertThat(undo.openLatest(player)).isEmpty();
    }

    @Test
    void undoReturnsNewestPerPlayer() {
        SqliteUndoStack undo = new SqliteUndoStack(store);
        UUID player = UUID.randomUUID();
        undo.pushReference(player, "ROLLBACK", "older");
        undo.pushReference(player, "RESTORE", "newer");
        UndoStack.Popped op = undo.openLatest(player).orElseThrow();
        assertThat(op.operationType()).isEqualTo("RESTORE");
        assertThat(((UndoStack.ReplayReference) op).referenceBase64()).isEqualTo("newer");
    }

    @Test
    void toolStateEnableDisableLoad() {
        SqliteToolStateStore tools = new SqliteToolStateStore(store);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        assertThat(tools.loadActive()).isEmpty();

        tools.enable(a);
        tools.enable(b);
        tools.enable(a); // idempotent upsert
        assertThat(tools.loadActive()).containsExactlyInAnyOrder(a, b);

        tools.disable(a);
        assertThat(tools.loadActive()).containsExactly(b);
    }

    @Test
    void salvageSaveGetReplaceDelete() {
        SqliteSalvageStore salvage = new SqliteSalvageStore(store, 30L);
        UUID id = UUID.randomUUID();
        UUID rollbackOp = UUID.randomUUID();
        SalvageSnapshot snapshot = new SalvageSnapshot(id, rollbackOp,
                UUID.randomUUID(), "world", 1, 64, 2, "CHEST", "Operator",
                java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS),
                List.of(new StoredItem(0, "DIAMOND", "minecraft:diamond"),
                        new StoredItem(1, "GOLD_INGOT", "minecraft:gold_ingot")));

        salvage.save(snapshot);
        Optional<SalvageSnapshot> fetched = salvage.get(id);
        assertThat(fetched).isPresent();
        assertThat(fetched.get().items()).hasSize(2);
        assertThat(fetched.get().containerType()).isEqualTo("CHEST");
        assertThat(fetched.get().operatorName()).isEqualTo("Operator");

        assertThat(salvage.list(10)).hasSize(1);
        List<SalvageStore.RollbackGroup> groups = salvage.listRollbacks(10);
        assertThat(groups).singleElement().satisfies(g -> {
            assertThat(g.rollbackId()).isEqualTo(rollbackOp);
            assertThat(g.containerCount()).isEqualTo(1);
        });
        assertThat(salvage.listByRollback(rollbackOp, 10)).hasSize(1);

        salvage.replaceItems(id, List.of(new StoredItem(0, "DIAMOND", "minecraft:diamond")));
        assertThat(salvage.get(id).orElseThrow().items()).hasSize(1);

        salvage.delete(id);
        assertThat(salvage.get(id)).isEmpty();
        assertThat(salvage.list(10)).isEmpty();
    }
}
