package net.medievalrp.spyglass.plugin.mariadb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.medievalrp.spyglass.api.event.StoredItem;
import net.medievalrp.spyglass.plugin.command.service.tool.MariaDbToolStateStore;
import net.medievalrp.spyglass.plugin.rollback.MariaDbUndoStack;
import net.medievalrp.spyglass.plugin.rollback.UndoStack;
import net.medievalrp.spyglass.plugin.salvage.MariaDbSalvageStore;
import net.medievalrp.spyglass.plugin.salvage.SalvageSnapshot;
import net.medievalrp.spyglass.plugin.salvage.SalvageStore;
import net.medievalrp.spyglass.plugin.storage.MariaDbRecordStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Covers the three MariaDB auxiliary stores (#169) - undo ledger, wand
 * state, salvage - sharing the record store's connection, against a real
 * InnoDB engine. Mirrors {@code SqliteAuxStoresTest}. Requires Docker.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MariaDbAuxStoresIT {

    private MariaDBContainer<?> container;
    private String host;
    private int port;
    private String db;
    private String user;
    private String pw;
    private MariaDbRecordStore store;

    @BeforeAll
    void setup() {
        assumeThat(DockerClientFactory.instance().isDockerAvailable())
                .as("docker not available")
                .isTrue();
        container = new MariaDBContainer<>(DockerImageName.parse("mariadb:11"));
        container.start();
        host = container.getHost();
        port = container.getFirstMappedPort();
        db = container.getDatabaseName();
        user = container.getUsername();
        pw = container.getPassword();
    }

    @AfterAll
    void teardown() {
        if (container != null) {
            container.stop();
        }
    }

    @BeforeEach
    void fresh() throws SQLException {
        try (Connection conn = DriverManager.getConnection(
                "jdbc:mariadb://" + host + ":" + port + "/" + db, user, pw);
             Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS undo_history");
            st.execute("DROP TABLE IF EXISTS tool_states");
            st.execute("DROP TABLE IF EXISTS salvage");
            st.execute("DROP TABLE IF EXISTS records");
            st.execute("DROP TABLE IF EXISTS dict");
            st.execute("DROP TABLE IF EXISTS uuids");
        }
        store = new MariaDbRecordStore(host, port, db, user, pw, false, 3600L);
    }

    @AfterEach
    void close() {
        if (store != null) {
            store.close();
        }
    }

    @Test
    void undoPushOpenAndTombstone() {
        MariaDbUndoStack undo = new MariaDbUndoStack(store);
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
        MariaDbUndoStack undo = new MariaDbUndoStack(store);
        UUID player = UUID.randomUUID();
        undo.pushReference(player, "ROLLBACK", "older");
        undo.pushReference(player, "RESTORE", "newer");
        UndoStack.Popped op = undo.openLatest(player).orElseThrow();
        assertThat(op.operationType()).isEqualTo("RESTORE");
        assertThat(((UndoStack.ReplayReference) op).referenceBase64()).isEqualTo("newer");
    }

    @Test
    void toolStateEnableDisableLoad() {
        MariaDbToolStateStore tools = new MariaDbToolStateStore(store);
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
        MariaDbSalvageStore salvage = new MariaDbSalvageStore(store, 30L);
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
