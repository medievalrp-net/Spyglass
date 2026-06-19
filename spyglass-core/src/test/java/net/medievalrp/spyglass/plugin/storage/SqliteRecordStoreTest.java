package net.medievalrp.spyglass.plugin.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Embedded-SQLite store coverage (#106) — runs with no Docker, a CI win
 * over the Testcontainers Mongo / ClickHouse ITs. Exercises the hybrid
 * schema (simple block → columns, everything else → blob), the keyset
 * page, the lean rollback stream in both directions, predicate pushdown +
 * post-filter, palette interning, and retention.
 */
class SqliteRecordStoreTest {

    private static final UUID WORLD = UUID.fromString("00000000-0000-0000-0000-0000000000ab");
    // occurred is stored at second precision, so truncate fixtures to match
    // for exact round-trip equality.
    private static final Instant BASE = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    // Column-stored rows reconstruct expiresAt as occurred + retention, so
    // the fixtures use occurred + RETENTION (3600s) as their expiry.
    private static final long RETENTION = 3600L;

    @TempDir
    Path dir;
    private SqliteRecordStore store;

    @BeforeEach
    void open() {
        store = new SqliteRecordStore(dir.resolve("spyglass.db"), false, RETENTION);
    }

    @AfterEach
    void close() {
        store.close();
    }

    // ===== Builders ============================================

    private static BlockSnapshot simple(Material material, String data) {
        return new BlockSnapshot(material, data, List.of(), List.of(), List.of(), List.of(), null);
    }

    private static BlockSnapshot sign() {
        // A non-empty sign line => not simple => the blob path.
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
        // "say" is the catalog event name for ChatRecord (not "chat").
        return new ChatRecord(EventIds.newId(), "say", BASE, BASE.plusSeconds(3600),
                Origin.player(), Source.player(UUID.randomUUID(), "Dan"),
                new BlockLocation(WORLD, "world", 0, 64, 0), "srv", null,
                message, List.of(), Map.of());
    }

    private static QueryRequest request(List<QueryPredicate> predicates) {
        return new QueryRequest(predicates, Sort.NEWEST_FIRST, 1000,
                EnumSet.noneOf(Flag.class), false);
    }

    private byte[] rawBlob(long seq) throws SQLException {
        store.checkpoint();
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + store.databaseFile());
             var ps = conn.prepareStatement("SELECT blob FROM records WHERE seq = ?")) {
            ps.setLong(1, seq);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getBytes(1) : new byte[0];
            }
        }
    }

    private long rawCount(String table) throws SQLException {
        store.checkpoint();
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + store.databaseFile());
             var st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM " + table)) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    // ===== Tests ===============================================

    @Test
    void simpleBlockRoundTripsThroughColumnsWithNoBlob() throws SQLException {
        BlockBreakRecord record = simpleBreak(10);
        store.save(List.of(record));

        QueryResult result = store.query(request(List.of()));
        assertThat(result.records()).containsExactly(record);
        // The whole record reduced to columns — no per-event blob row.
        assertThat(rawBlob(EventIds.sequenceOf(record.id()))).isNull();
    }

    @Test
    void tileEntityBlockRoundTripsThroughBlob() throws SQLException {
        BlockBreakRecord record = breakAt(UUID.randomUUID(), "Alice", 1, 1,
                sign(), simple(Material.AIR, "minecraft:air"));
        store.save(List.of(record));

        QueryResult result = store.query(request(List.of()));
        assertThat(result.records()).containsExactly(record);
        // A tile-entity block can't reduce to columns; it carries a blob.
        assertThat(rawBlob(EventIds.sequenceOf(record.id()))).isNotEmpty();
    }

    @Test
    void nonBlockEventsRoundTripThroughBlob() {
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

        // chat is filtered out at the SQL level (not rollbackable), so it
        // never reaches the sink at all.
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

        // Newest-first is by seq (the mint sequence == rowid), so paging
        // walks every row exactly once in reverse save order — last-minted
        // first. seq is co-monotonic with occurred for real records.
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
        // Three breaks by one player in one world: world + player UUIDs and
        // the "break"/"world"/"minecraft:stone"/… strings intern once each.
        store.save(List.of(
                breakAt(player, "Zed", 1, 1, simple(Material.STONE, "minecraft:stone"),
                        simple(Material.AIR, "minecraft:air")),
                breakAt(player, "Zed", 2, 1, simple(Material.STONE, "minecraft:stone"),
                        simple(Material.AIR, "minecraft:air")),
                breakAt(player, "Zed", 3, 1, simple(Material.STONE, "minecraft:stone"),
                        simple(Material.AIR, "minecraft:air"))));
        // Exactly two UUIDs (world + player) despite three rows.
        assertThat(rawCount("uuids")).isEqualTo(2L);
        // event, world-name, target(STONE), player-name, STONE/AIR materials,
        // stone/air block-data, server, origin/source kinds — a small fixed
        // set, NOT three-per-row.
        assertThat(rawCount("dict")).isLessThan(12L);
    }

    @Test
    void retentionPrunesExpiredRows() throws SQLException {
        BlockBreakRecord fresh = simpleBreak(1);
        // occurred is older than the retention window (3600s), so the sweep
        // (DELETE WHERE occurred < now - retention) drops it.
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
