package net.medievalrp.spyglass.plugin.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import net.medievalrp.spyglass.api.event.BlockBreakRecord;
import net.medievalrp.spyglass.api.event.BlockPlaceRecord;
import net.medievalrp.spyglass.api.event.BlockSnapshot;
import net.medievalrp.spyglass.api.event.ChatRecord;
import net.medievalrp.spyglass.api.event.ContainerDepositRecord;
import net.medievalrp.spyglass.api.event.EntityDeathRecord;
import net.medievalrp.spyglass.api.event.EventRecord;
import net.medievalrp.spyglass.api.event.Origin;
import net.medievalrp.spyglass.api.event.Source;
import net.medievalrp.spyglass.api.event.StoredItem;
import net.medievalrp.spyglass.api.event.TeleportRecord;
import net.medievalrp.spyglass.api.query.Flag;
import net.medievalrp.spyglass.api.query.QueryPredicate;
import net.medievalrp.spyglass.api.query.QueryRequest;
import net.medievalrp.spyglass.api.query.QueryResult;
import net.medievalrp.spyglass.api.query.Sort;
import net.medievalrp.spyglass.api.rollback.RollbackEffect;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.api.util.EventIds;
import org.bukkit.Material;
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
 * MariaDB store coverage (#169) via Testcontainers. The same suite the
 * SQLite store runs (hybrid schema simple-block -> columns / everything ->
 * payload, keyset page, lean rollback stream both directions, predicate
 * pushdown + post-filter, palette interning, retention), proving parity
 * against the client-server InnoDB engine. Requires Docker.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MariaDbRecordStoreIT {

    private static final UUID WORLD = UUID.fromString("00000000-0000-0000-0000-0000000000ab");
    // occurred is stored at second precision, so truncate fixtures to match.
    private static final Instant BASE = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    // Column-stored rows reconstruct expiresAt as occurred + retention.
    private static final long RETENTION = 3600L;

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
        // The server persists between tests; drop the data tables so each
        // test starts empty (and the reopened store's palette caches start
        // clean - truncating the palette under a live store would desync it).
        try (Connection conn = direct(); Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS records");
            st.execute("DROP TABLE IF EXISTS dict");
            st.execute("DROP TABLE IF EXISTS uuids");
        }
        store = new MariaDbRecordStore(host, port, db, user, pw, false, RETENTION);
    }

    @AfterEach
    void close() {
        if (store != null) {
            store.close();
        }
    }

    private Connection direct() throws SQLException {
        return DriverManager.getConnection("jdbc:mariadb://" + host + ":" + port + "/" + db, user, pw);
    }

    // ===== Builders (shared with the SQLite suite) =============

    private static BlockSnapshot simple(Material material, String data) {
        return new BlockSnapshot(material, data, List.of(), List.of(), List.of(), List.of(), null);
    }

    private static BlockSnapshot sign() {
        return new BlockSnapshot(Material.OAK_SIGN, "minecraft:oak_sign",
                List.of(), List.of("line"), List.of(), List.of(), null);
    }

    private BlockBreakRecord breakAt(UUID player, String name, int x, long secondsAgo,
                                     BlockSnapshot before, BlockSnapshot after) {
        Instant occurred = BASE.minusSeconds(secondsAgo);
        return new BlockBreakRecord(EventIds.newId(), "break", occurred, occurred.plusSeconds(3600),
                Origin.player(), Source.player(player, name),
                new BlockLocation(WORLD, "world", x, 64, x), "srv", "STONE", before, after);
    }

    private BlockBreakRecord simpleBreak(int x) {
        return breakAt(UUID.randomUUID(), "Alice", x, x,
                simple(Material.STONE, "minecraft:stone"), simple(Material.AIR, "minecraft:air"));
    }

    private ContainerDepositRecord deposit(int slot) {
        Instant occurred = BASE.minusSeconds(slot);
        return new ContainerDepositRecord(EventIds.newId(), "deposit", occurred, occurred.plusSeconds(3600),
                Origin.player(), Source.player(UUID.randomUUID(), "Bob"),
                new BlockLocation(WORLD, "world", slot, 70, slot), "srv", "CHEST",
                "CHEST", slot, 1,
                new StoredItem(slot, "DIAMOND", "minecraft:diamond"), null);
    }

    private EntityDeathRecord death(String type) {
        return new EntityDeathRecord(EventIds.newId(), "death", BASE, BASE.plusSeconds(3600),
                Origin.player(), Source.player(UUID.randomUUID(), "Cara"),
                new BlockLocation(WORLD, "world", 5, 64, 5), "srv", type,
                type, UUID.randomUUID(), "PLAYER", "ENTITY_ATTACK", null);
    }

    private ChatRecord chat(String message) {
        return new ChatRecord(EventIds.newId(), "say", BASE, BASE.plusSeconds(3600),
                Origin.player(), Source.player(UUID.randomUUID(), "Dan"),
                new BlockLocation(WORLD, "world", 0, 64, 0), "srv", null,
                message, List.of(), Map.of());
    }

    private static QueryRequest request(List<QueryPredicate> predicates) {
        return new QueryRequest(predicates, Sort.NEWEST_FIRST, 1000,
                EnumSet.noneOf(Flag.class), false);
    }

    private byte[] rawPayload(long seq) throws SQLException {
        try (Connection conn = direct();
             var ps = conn.prepareStatement("SELECT payload FROM records WHERE seq = ?")) {
            ps.setLong(1, seq);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getBytes(1) : new byte[0];
            }
        }
    }

    private long rawCount(String table) throws SQLException {
        try (Connection conn = direct();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM " + table)) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    // ===== Tests ===============================================

    @Test
    void registersMariaDbDriverOnClassLoad() {
        boolean registered = DriverManager.drivers()
                .anyMatch(d -> d.getClass().getName().equals("org.mariadb.jdbc.Driver"));
        assertThat(registered)
                .as("MariaDbRecordStore must self-register the org.mariadb.jdbc.Driver")
                .isTrue();
    }

    @Test
    void simpleBlockRoundTripsThroughColumnsWithNoPayload() throws SQLException {
        BlockBreakRecord record = simpleBreak(10);
        store.save(List.of(record));

        QueryResult result = store.query(request(List.of()));
        assertThat(result.records()).containsExactly(record);
        assertThat(rawPayload(EventIds.sequenceOf(record.id()))).isNull();
    }

    @Test
    void tileEntityBlockRoundTripsThroughPayload() throws SQLException {
        BlockBreakRecord record = breakAt(UUID.randomUUID(), "Alice", 1, 1,
                sign(), simple(Material.AIR, "minecraft:air"));
        store.save(List.of(record));

        QueryResult result = store.query(request(List.of()));
        assertThat(result.records()).containsExactly(record);
        assertThat(rawPayload(EventIds.sequenceOf(record.id()))).isNotEmpty();
    }

    @Test
    void nonBlockEventsRoundTripThroughPayload() {
        BlockPlaceRecord place = new BlockPlaceRecord(EventIds.newId(), "place", BASE, BASE.plusSeconds(3600),
                Origin.player(), Source.player(UUID.randomUUID(), "Eve"),
                new BlockLocation(WORLD, "world", 2, 65, 2), "srv", "DIRT",
                simple(Material.AIR, "minecraft:air"), simple(Material.DIRT, "minecraft:dirt"));
        TeleportRecord teleport = new TeleportRecord(EventIds.newId(), "teleport", BASE, BASE.plusSeconds(3600),
                Origin.player(), Source.player(UUID.randomUUID(), "Finn"),
                new BlockLocation(WORLD, "world", 3, 64, 3), "srv", null,
                new BlockLocation(WORLD, "world", 3, 64, 3),
                new BlockLocation(WORLD, "world", 99, 64, 99), "COMMAND");
        List<EventRecord> records = List.of(place, deposit(4), death("ZOMBIE"), chat("hi"), teleport);
        store.save(records);

        QueryResult result = store.query(request(List.of()));
        assertThat(result.records()).containsExactlyInAnyOrderElementsOf(records);
    }

    @Test
    void streamRollbackEffectsEmitsSimpleBlockAsPrimitives() {
        BlockBreakRecord record = breakAt(UUID.randomUUID(), "Alice", 7, 1,
                simple(Material.STONE, "minecraft:stone"), simple(Material.AIR, "minecraft:air"));
        store.save(List.of(record));

        CapturingSink rollback = new CapturingSink();
        store.streamRollbackEffects(request(List.of()), null, 1000, true, rollback);
        assertThat(rollback.complex).isEmpty();
        assertThat(rollback.blocks).singleElement().satisfies(b -> {
            assertThat(b.data()).isEqualTo("minecraft:stone"); // rollback writes the before-state
            assertThat(b.x()).isEqualTo(7);
            assertThat(b.world()).isEqualTo(WORLD);
        });

        CapturingSink restore = new CapturingSink();
        store.streamRollbackEffects(request(List.of()), null, 1000, false, restore);
        assertThat(restore.blocks).singleElement().satisfies(
                b -> assertThat(b.data()).isEqualTo("minecraft:air")); // restore writes the after-state
    }

    @Test
    void streamRollbackEffectsEmitsContainerAndEntityAsComplexAndSkipsChat() {
        store.save(List.of(deposit(1), death("ZOMBIE"), chat("noise")));

        CapturingSink sink = new CapturingSink();
        store.streamRollbackEffects(request(List.of()), null, 1000, true, sink);

        // chat is filtered out at the SQL level (not rollbackable).
        assertThat(sink.skips).isZero();
        assertThat(sink.blocks).isEmpty();
        assertThat(sink.complex).hasSize(2);
        assertThat(sink.complex).anySatisfy(e ->
                assertThat(e).isInstanceOf(RollbackEffect.ContainerSlotWrite.class));
        assertThat(sink.complex).anySatisfy(e ->
                assertThat(e).isInstanceOf(RollbackEffect.EntitySpawn.class));
    }

    @Test
    void keysetPageReturnsEveryRowOnceInOrder() {
        List<EventRecord> saved = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            saved.add(simpleBreak(i));
        }
        store.save(saved);

        List<UUID> seen = new ArrayList<>();
        QueryPage.Cursor cursor = null;
        do {
            QueryPage page = store.queryPage(request(List.of()), cursor, 7);
            page.records().forEach(r -> seen.add(r.id()));
            cursor = page.next();
        } while (cursor != null);

        List<UUID> newestFirst = new ArrayList<>(saved.stream().map(EventRecord::id).toList());
        java.util.Collections.reverse(newestFirst);
        assertThat(seen).containsExactlyElementsOf(newestFirst);
    }

    @Test
    void predicatePushdownFiltersByPlayerEventAndTime() {
        UUID alice = UUID.randomUUID();
        BlockBreakRecord aliceBreak = breakAt(alice, "Alice", 1, 10,
                simple(Material.STONE, "minecraft:stone"), simple(Material.AIR, "minecraft:air"));
        BlockBreakRecord bobBreak = breakAt(UUID.randomUUID(), "Bob", 2, 10,
                simple(Material.DIRT, "minecraft:dirt"), simple(Material.AIR, "minecraft:air"));
        store.save(List.of(aliceBreak, bobBreak, chat("hi")));

        QueryResult byPlayer = store.query(request(List.of(
                new QueryPredicate.Eq("source.playerId", alice))));
        assertThat(byPlayer.records()).containsExactly(aliceBreak);

        QueryResult byEvent = store.query(request(List.of(
                new QueryPredicate.Eq("event", "break"))));
        assertThat(byEvent.records()).containsExactlyInAnyOrder(aliceBreak, bobBreak);

        QueryResult byTime = store.query(request(List.of(
                new QueryPredicate.Range("occurred", BASE.minusSeconds(20), BASE.minusSeconds(5)))));
        assertThat(byTime.records()).containsExactlyInAnyOrder(aliceBreak, bobBreak);
    }

    @Test
    void absentPaletteValueMatchesNothing() {
        store.save(List.of(simpleBreak(1)));
        QueryResult result = store.query(request(List.of(
                new QueryPredicate.Eq("source.playerId", UUID.randomUUID())))); // never recorded
        assertThat(result.records()).isEmpty();
    }

    @Test
    void coordinateRangeReturnsBlocksInBox() {
        store.save(List.of(simpleBreak(5), simpleBreak(50), simpleBreak(500)));
        QueryResult inBox = store.query(request(List.of(
                new QueryPredicate.Range("location.x", 0, 100),
                new QueryPredicate.Range("location.z", 0, 100))));
        // x and z are equal in simpleBreak(n); only n in [0,100] qualifies.
        assertThat(inBox.records()).hasSize(2);
    }

    @Test
    void regexPredicateFallsToPostFilter() {
        store.save(List.of(
                breakAt(UUID.randomUUID(), "Alice", 1, 1,
                        simple(Material.STONE, "minecraft:stone"), simple(Material.AIR, "minecraft:air")),
                breakAt(UUID.randomUUID(), "Bartholomew", 2, 1,
                        simple(Material.DIRT, "minecraft:dirt"), simple(Material.AIR, "minecraft:air"))));

        QueryResult result = store.query(request(List.of(new QueryPredicate.Eq(
                "source.playerName", Pattern.compile("Alic.*")))));
        assertThat(result.records()).singleElement().satisfies(
                r -> assertThat(r.sourceName()).isEqualTo("Alice"));
    }

    @Test
    void paletteInternsRepeatedValuesOnce() throws SQLException {
        UUID player = UUID.randomUUID();
        store.save(List.of(
                breakAt(player, "Zed", 1, 1, simple(Material.STONE, "minecraft:stone"),
                        simple(Material.AIR, "minecraft:air")),
                breakAt(player, "Zed", 2, 1, simple(Material.STONE, "minecraft:stone"),
                        simple(Material.AIR, "minecraft:air")),
                breakAt(player, "Zed", 3, 1, simple(Material.STONE, "minecraft:stone"),
                        simple(Material.AIR, "minecraft:air"))));
        // Exactly two UUIDs (world + player) despite three rows.
        assertThat(rawCount("uuids")).isEqualTo(2L);
        // A small fixed set of strings, NOT three-per-row.
        assertThat(rawCount("dict")).isLessThan(12L);
    }

    @Test
    void retentionPrunesExpiredRows() throws SQLException {
        BlockBreakRecord fresh = simpleBreak(1);
        Instant old = BASE.minusSeconds(2 * RETENTION);
        BlockBreakRecord stale = new BlockBreakRecord(EventIds.newId(), "break",
                old, old.plusSeconds(RETENTION),
                Origin.player(), Source.player(UUID.randomUUID(), "Old"),
                new BlockLocation(WORLD, "world", 9, 64, 9), "srv", "STONE",
                simple(Material.STONE, "minecraft:stone"), simple(Material.AIR, "minecraft:air"));
        store.save(List.of(fresh, stale));
        assertThat(rawCount("records")).isEqualTo(2L);

        long pruned = store.pruneExpired();
        assertThat(pruned).isEqualTo(1L);
        assertThat(store.query(request(List.of())).records()).containsExactly(fresh);
    }

    private static final class CapturingSink implements RecordStore.RollbackEffectSink {
        record Block(UUID world, int x, int y, int z, String data) {
        }

        final List<Block> blocks = new ArrayList<>();
        final List<RollbackEffect> complex = new ArrayList<>();
        int skips;

        @Override
        public void block(UUID world, int x, int y, int z, String data, String expected,
                          Instant occurred, UUID id) {
            blocks.add(new Block(world, x, y, z, data));
        }

        @Override
        public void complex(RollbackEffect effect, Instant occurred, UUID id) {
            complex.add(effect);
        }

        @Override
        public void skip(Instant occurred, UUID id) {
            skips++;
        }
    }
}
