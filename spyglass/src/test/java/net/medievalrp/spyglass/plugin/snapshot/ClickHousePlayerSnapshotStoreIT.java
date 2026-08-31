package net.medievalrp.spyglass.plugin.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assumptions.assumeThat;

import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.medievalrp.spyglass.api.event.StoredItem;
import net.medievalrp.spyglass.api.util.EventIds;
import net.medievalrp.spyglass.plugin.storage.ClickHouseRecordStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.clickhouse.ClickHouseContainer;

/**
 * {@link ClickHousePlayerSnapshotStore} against a real ClickHouse. The record
 * store bootstraps the schema (including the two snapshot tables this store
 * reads), then we exercise save/read, latest-at-or-before selection, the
 * synchronous read-your-writes contract, intern dedupe (asserted over the
 * intern table with the driver), and prune. Each test uses its own player /
 * unique payloads, so no cross-test wipe is needed.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ClickHousePlayerSnapshotStoreIT {

    private static final HexFormat HEX = HexFormat.of().withUpperCase();

    private ClickHouseContainer container;
    private ClickHouseRecordStore store;
    private ClickHousePlayerSnapshotStore snapStore;

    @BeforeAll
    void setup() {
        assumeThat(DockerClientFactory.instance().isDockerAvailable())
                .as("docker not available")
                .isTrue();
        container = new ClickHouseContainer("clickhouse/clickhouse-server:24.8-alpine");
        container.start();
        store = new ClickHouseRecordStore(
                container.getHost(),
                container.getMappedPort(8123),
                "spyglass_it",
                "event_records_it",
                container.getUsername(),
                container.getPassword(),
                false);
        snapStore = new ClickHousePlayerSnapshotStore(store.client(), "spyglass_it");
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

    // --- helpers ---------------------------------------------------

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

    private static String expectedHex(byte[] raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw);
            return HEX.formatHex(Arrays.copyOf(digest, 16));
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private long internCount(String hex) {
        return store.client().queryAll(
                "SELECT count() AS c FROM `spyglass_it`.`snapshot_items` FINAL "
                        + "WHERE hex(hash) = '" + hex + "'").get(0).getLong("c");
    }

    private long snapshotCount(UUID player) {
        return store.client().queryAll(
                "SELECT count() AS c FROM `spyglass_it`.`player_snapshots` FINAL "
                        + "WHERE player_uuid = toUUID('" + player + "')").get(0).getLong("c");
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
    void readYourWritesImmediatelyAfterSave() {
        UUID player = UUID.randomUUID();
        Instant at = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        snapStore.save(snapshot(player, at, 7L, List.of(
                new SnapshotSlot(0, 1, item(0, "minecraft:torch", payload("torch"))))));
        // No sleep: the synchronous insert must be visible immediately.
        assertThat(snapStore.latestAtOrBefore(player, at)).isPresent();
        assertThat(snapStore.lastContentHash(player)).hasValue(7L);
    }

    @Test
    void internDedupesAcrossSnapshotsAndDoubleInternDoesNotThrow() {
        byte[] shared = payload("shared-kit");
        String hex = expectedHex(shared);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();

        assertThatCode(() -> {
            snapStore.save(snapshot(a, Instant.now().truncatedTo(ChronoUnit.MILLIS), 1L, List.of(
                    new SnapshotSlot(0, 1, item(0, "minecraft:shield", shared)))));
            // Second snapshot re-interns the identical payload: must not throw.
            snapStore.save(snapshot(b, Instant.now().truncatedTo(ChronoUnit.MILLIS), 2L, List.of(
                    new SnapshotSlot(0, 1, item(0, "minecraft:shield", shared)))));
        }).doesNotThrowAnyException();

        assertThat(internCount(hex)).as("payload interned exactly once").isEqualTo(1L);
    }

    @Test
    void pruneRemovesOldSnapshotRows() {
        UUID oldPlayer = UUID.randomUUID();
        UUID newPlayer = UUID.randomUUID();
        Instant old = Instant.now().minus(40, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS);
        Instant fresh = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        snapStore.save(snapshot(oldPlayer, old, 1L, List.of(
                new SnapshotSlot(0, 1, item(0, "minecraft:stone", payload("old"))))));
        snapStore.save(snapshot(newPlayer, fresh, 2L, List.of(
                new SnapshotSlot(0, 1, item(0, "minecraft:dirt", payload("new"))))));

        int removed = snapStore.prune(Instant.now().minus(30, ChronoUnit.DAYS));
        assertThat(removed).isEqualTo(1);
        assertThat(snapshotCount(oldPlayer)).as("old snapshot rows gone").isZero();
        assertThat(snapStore.latestAtOrBefore(oldPlayer, fresh)).isEmpty();
        assertThat(snapStore.latestAtOrBefore(newPlayer, fresh)).isPresent();
    }
}
