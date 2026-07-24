package net.medievalrp.spyglass.plugin.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assumptions.assumeThat;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import net.medievalrp.spyglass.api.event.StoredItem;
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
 * Covers the MariaDB {@link PlayerSnapshotStore} (#341) against a real InnoDB
 * engine via Testcontainers. Mirrors {@code SqlitePlayerSnapshotStoreTest}
 * plus the recorder-retry case (a second {@code save} of an already-committed
 * capture) that only ever failed live with a Duplicate entry before the
 * upsert idiom landed. Requires Docker; assume-skips cleanly without it.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MariaDbPlayerSnapshotStoreIT {

    private MariaDBContainer<?> container;
    private String host;
    private int port;
    private String db;
    private String user;
    private String pw;
    private MariaDbRecordStore store;
    private MariaDbPlayerSnapshotStore snapshots;

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
        try (Connection conn = DriverManager.getConnection(jdbcUrl(), user, pw);
             Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS player_snapshot_slots");
            st.execute("DROP TABLE IF EXISTS player_snapshots");
            st.execute("DROP TABLE IF EXISTS snapshot_items");
            st.execute("DROP TABLE IF EXISTS records");
            st.execute("DROP TABLE IF EXISTS dict");
            st.execute("DROP TABLE IF EXISTS uuids");
        }
        store = new MariaDbRecordStore(host, port, db, user, pw, false, 3600L);
        snapshots = new MariaDbPlayerSnapshotStore(store);
    }

    @AfterEach
    void close() {
        if (store != null) {
            store.close();
        }
    }

    @Test
    void saveReadRoundtripKeepsSlotFidelityAndMaterial() {
        UUID player = UUID.randomUUID();
        PlayerSnapshot snap = new PlayerSnapshot(UUID.randomUUID(), player, "Alice",
                Instant.ofEpochMilli(2_000_000L), PlayerSnapshot.CAUSE_SWEEP, 0xABCDL,
                List.of(slot(0, 64, "COBBLESTONE", "cobble-payload"),
                        slot(40, 1, "SHIELD", "shield-payload")));
        snapshots.save(snap);

        PlayerSnapshot got = snapshots.latestAtOrBefore(player, Instant.ofEpochMilli(3_000_000L)).orElseThrow();
        assertThat(got.id()).isEqualTo(snap.id());
        assertThat(got.player()).isEqualTo(player);
        assertThat(got.playerName()).isEqualTo("Alice");
        assertThat(got.cause()).isEqualTo(PlayerSnapshot.CAUSE_SWEEP);
        assertThat(got.contentHash()).isEqualTo(0xABCDL);
        assertThat(got.capturedAt()).isEqualTo(Instant.ofEpochMilli(2_000_000L));
        assertThat(got.slots()).hasSize(2);

        SnapshotSlot s0 = got.slots().get(0);
        assertThat(s0.slot()).isEqualTo(0);
        assertThat(s0.count()).isEqualTo(64);
        assertThat(s0.item().material()).isEqualTo("COBBLESTONE");
        assertThat(s0.item().data()).isEqualTo(base64("cobble-payload"));

        SnapshotSlot s1 = got.slots().get(1);
        assertThat(s1.slot()).isEqualTo(40);
        assertThat(s1.count()).isEqualTo(1);
        assertThat(s1.item().material()).isEqualTo("SHIELD");
        assertThat(s1.item().data()).isEqualTo(base64("shield-payload"));
    }

    @Test
    void latestAtOrBeforePicksTheCorrectRow() {
        UUID player = UUID.randomUUID();
        snapshots.save(snapshot(player, "P", 1_000L, 111L, PlayerSnapshot.CAUSE_JOIN,
                slot(0, 1, "DIRT", "d1")));
        snapshots.save(snapshot(player, "P", 2_000L, 222L, PlayerSnapshot.CAUSE_SWEEP,
                slot(0, 1, "DIRT", "d2")));
        snapshots.save(snapshot(player, "P", 3_000L, 333L, PlayerSnapshot.CAUSE_QUIT,
                slot(0, 1, "DIRT", "d3")));

        assertThat(snapshots.latestAtOrBefore(player, Instant.ofEpochMilli(2_500L)).orElseThrow()
                .contentHash()).isEqualTo(222L);
        assertThat(snapshots.latestAtOrBefore(player, Instant.ofEpochMilli(3_000L)).orElseThrow()
                .contentHash()).isEqualTo(333L);
        assertThat(snapshots.latestAtOrBefore(player, Instant.ofEpochMilli(500L))).isEmpty();
    }

    @Test
    void internDedupesSharedPayloadsAcrossSnapshots() throws SQLException {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        snapshots.save(snapshot(a, "A", 1_000L, 1L, PlayerSnapshot.CAUSE_SWEEP,
                slot(0, 1, "DIAMOND", "shared"), slot(1, 1, "GOLD_INGOT", "gold")));
        snapshots.save(snapshot(b, "B", 1_000L, 2L, PlayerSnapshot.CAUSE_SWEEP,
                slot(0, 1, "DIAMOND", "shared"), slot(1, 1, "IRON_INGOT", "iron")));

        assertThat(countItems()).isEqualTo(3L);
    }

    @Test
    void reSaveOfCommittedCaptureIsIdempotent() throws SQLException {
        UUID player = UUID.randomUUID();
        PlayerSnapshot snap = snapshot(player, "P", 5_000L, 9L, PlayerSnapshot.CAUSE_SWEEP,
                slot(0, 1, "NETHERITE_INGOT", "ning"), slot(1, 1, "GOLD_INGOT", "gold"));
        snapshots.save(snap);

        // The recorder retry re-interns already-committed payloads and re-inserts
        // the same snapshot id; a plain INSERT would throw Duplicate entry here.
        assertThatCode(() -> snapshots.save(snap)).doesNotThrowAnyException();

        assertThat(countItems()).isEqualTo(2L);
        assertThat(snapshots.latestAtOrBefore(player, Instant.ofEpochMilli(6_000L)).orElseThrow()
                .slots()).hasSize(2);
    }

    @Test
    void pruneRemovesOldRowsAndOrphanedBlobsButKeepsReferencedOnes() throws SQLException {
        UUID player = UUID.randomUUID();
        snapshots.save(snapshot(player, "P", 1_000L, 1L, PlayerSnapshot.CAUSE_JOIN,
                slot(0, 1, "STONE", "X"), slot(1, 1, "GLASS", "Y")));
        snapshots.save(snapshot(player, "P", 10_000L, 2L, PlayerSnapshot.CAUSE_SWEEP,
                slot(0, 1, "GLASS", "Y"), slot(1, 1, "SAND", "Z")));
        assertThat(countItems()).isEqualTo(3L);

        int removed = snapshots.prune(Instant.ofEpochMilli(5_000L));
        assertThat(removed).isEqualTo(1);

        assertThat(countItems()).isEqualTo(2L);

        PlayerSnapshot survivor = snapshots.latestAtOrBefore(player, Instant.ofEpochMilli(20_000L)).orElseThrow();
        assertThat(survivor.contentHash()).isEqualTo(2L);
        assertThat(survivor.slots()).extracting(sl -> sl.item().data())
                .containsExactly(base64("Y"), base64("Z"));
        assertThat(snapshots.latestAtOrBefore(player, Instant.ofEpochMilli(1_000L))).isEmpty();
    }

    @Test
    void lastContentHashReturnsNewest() {
        UUID player = UUID.randomUUID();
        assertThat(snapshots.lastContentHash(player)).isEmpty();

        snapshots.save(snapshot(player, "P", 1_000L, 111L, PlayerSnapshot.CAUSE_JOIN,
                slot(0, 1, "DIRT", "a")));
        snapshots.save(snapshot(player, "P", 2_000L, 222L, PlayerSnapshot.CAUSE_SWEEP,
                slot(0, 1, "DIRT", "b")));

        assertThat(snapshots.lastContentHash(player)).hasValue(222L);
    }

    // ----- helpers -----

    private String jdbcUrl() {
        return "jdbc:mariadb://" + host + ":" + port + "/" + db;
    }

    private long countItems() throws SQLException {
        try (Connection conn = DriverManager.getConnection(jdbcUrl(), user, pw);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM snapshot_items")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static PlayerSnapshot snapshot(UUID player, String name, long occurredMs,
                                           long contentHash, String cause, SnapshotSlot... slots) {
        return new PlayerSnapshot(UUID.randomUUID(), player, name,
                Instant.ofEpochMilli(occurredMs), cause, contentHash, List.of(slots));
    }

    private static SnapshotSlot slot(int slot, int count, String material, String payload) {
        return new SnapshotSlot(slot, count, new StoredItem(slot, material, base64(payload)));
    }

    private static String base64(String payload) {
        return Base64.getEncoder().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }
}
