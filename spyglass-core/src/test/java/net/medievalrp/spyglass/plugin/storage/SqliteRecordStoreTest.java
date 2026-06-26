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
 * Embedded-SQLite store coverage (#106) - runs with no Docker, a CI win
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

    @Test
    void registersSqliteDriverOnClassLoad() {
        // The store's static initializer force-loads org.sqlite.JDBC so the
        // driver is registered with DriverManager even when sqlite-jdbc lives
        // in Paper's isolated library classloader (the lean jar). Constructing
        // the store in @BeforeEach has already triggered the static init, so
        // the driver must now be registered.
        boolean registered = DriverManager.drivers()
                .anyMatch(d -> d.getClass().getName().equals("org.sqlite.JDBC"));
        assertThat(registered)
                .as("SqliteRecordStore must self-register the org.sqlite.JDBC driver")
                .isTrue();
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
    void recipientSearchMatchesViaPostFilter() {
        // recipients live in the blob on SQLite -> post-filter. The evaluator
        // must resolve the list (membership) or rcp: silently matches nothing.
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        store.save(List.of(new ChatRecord(
                EventIds.newId(), "say", BASE, BASE.plusSeconds(3600),
                Origin.player(), Source.player(alice, "Alice"),
                new BlockLocation(WORLD, "world", 0, 64, 0), "srv",
                "hi", "hi", List.of(alice, bob), Map.of())));

        assertThat(store.query(request(List.of(new QueryPredicate.Eq("recipients", bob))))
                .records()).hasSize(1);
        assertThat(store.query(request(List.of(new QueryPredicate.Eq("recipients", UUID.randomUUID()))))
                .records()).isEmpty();
    }

    @Test
    void roundTripsKillAndMobKillAsEntityHitRecord() {
        // kill/mob-kill reuse EntityHitRecord; the catalog maps both names to it
        // so the read path decodes them instead of dropping the rows.
        Instant occurred = BASE.minusSeconds(5);
        net.medievalrp.spyglass.api.event.EntityHitRecord kill =
                new net.medievalrp.spyglass.api.event.EntityHitRecord(
                        EventIds.newId(), "kill", occurred, occurred.plusSeconds(RETENTION),
                        Origin.player(), Source.player(UUID.randomUUID(), "Alice"),
                        new BlockLocation(WORLD, "world", 1, 64, 2), "srv",
                        "zombie", "zombie", UUID.randomUUID(), 6.0, false, null);
        net.medievalrp.spyglass.api.event.EntityHitRecord mobKill =
                new net.medievalrp.spyglass.api.event.EntityHitRecord(
                        EventIds.newId(), "mob-kill", occurred, occurred.plusSeconds(RETENTION),
                        Origin.environment("kill:zombie"), Source.entity(UUID.randomUUID(), "zombie"),
                        new BlockLocation(WORLD, "world", 1, 64, 2), "srv",
                        "Bob", "player", UUID.randomUUID(), 4.0, false, null);
        store.save(List.of(kill, mobKill));

        var killBack = store.query(request(List.of(new QueryPredicate.Eq("event", "kill")))).records();
        assertThat(killBack).singleElement()
                .isInstanceOf(net.medievalrp.spyglass.api.event.EntityHitRecord.class);
        assertThat(killBack.get(0).event()).isEqualTo("kill");

        var mobBack = store.query(request(List.of(new QueryPredicate.Eq("event", "mob-kill")))).records();
        assertThat(mobBack).singleElement()
                .isInstanceOf(net.medievalrp.spyglass.api.event.EntityHitRecord.class);
        assertThat(mobBack.get(0).event()).isEqualTo("mob-kill");
    }

    @Test
    void contentSearchMatchesChatMessageViaPostFilter() {
        // On SQLite the message lives in the blob, so m:/content: always runs
        // as an in-memory post-filter. The evaluator must resolve `message`
        // (and commandLine) or the OR never matches.
        store.save(List.of(chat("hello there world")));
        Pattern lit = Pattern.compile(Pattern.quote("there"), Pattern.CASE_INSENSITIVE);
        var req = request(List.of(new QueryPredicate.Or(List.of(
                new QueryPredicate.Eq("message", lit),
                new QueryPredicate.Eq("commandLine", lit)))));
        assertThat(store.query(req).records()).hasSize(1);

        var miss = request(List.of(new QueryPredicate.Or(List.of(
                new QueryPredicate.Eq("message",
                        Pattern.compile(Pattern.quote("absent"), Pattern.CASE_INSENSITIVE)),
                new QueryPredicate.Eq("commandLine",
                        Pattern.compile(Pattern.quote("absent"), Pattern.CASE_INSENSITIVE))))));
        assertThat(store.query(miss).records()).isEmpty();
    }

    @Test
    void roundTripsRegisteredCustomEvent() {
        net.medievalrp.spyglass.api.event.EventCatalog.register("voice", "spoke");
        Instant occurred = BASE.minusSeconds(5);
        net.medievalrp.spyglass.api.event.CustomRecord rec =
                net.medievalrp.spyglass.api.event.CustomRecord.of(
                        new net.medievalrp.spyglass.api.event.RecordContext(
                                EventIds.newId(), occurred, occurred.plusSeconds(RETENTION),
                                Origin.player(), Source.player(UUID.randomUUID(), "Eve"),
                                new BlockLocation(WORLD, "world", 1, 64, 2), "srv", Map.of()),
                        "voice", "voice to 2 players", "hello there",
                        Map.of("voice_session_id", "42"));
        store.save(List.of(rec));

        var back = (net.medievalrp.spyglass.api.event.CustomRecord) store
                .query(request(List.of(new QueryPredicate.Eq("event", "voice"))))
                .records().get(0);
        assertThat(back.message()).isEqualTo("hello there");
        assertThat(back.target()).isEqualTo("voice to 2 players");
        assertThat(back.extensions()).containsEntry("voice_session_id", "42");

        // extensions.<key> filtering (post-filter; extensions live in the blob).
        var byExt = request(List.of(
                new QueryPredicate.Eq("event", "voice"),
                new QueryPredicate.Eq("extensions.voice_session_id", "42")));
        assertThat(store.query(byExt).records()).hasSize(1);
    }

    @Test
    void simpleBlockRoundTripsThroughColumnsWithNoBlob() throws SQLException {
        BlockBreakRecord record = simpleBreak(10);
        store.save(List.of(record));

        QueryResult result = store.query(request(List.of()));
        assertThat(result.records()).containsExactly(record);
        // The whole record reduced to columns - no per-event blob row.
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
        // walks every row exactly once in reverse save order - last-minted
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
    void itemTagFilterFallsToPostFilter() {
        // itags: lives inside the per-event blob (no column), so SQLite pushes
        // what it can and matches custom_data in memory (#140), the same
        // residual path as iname/ilore/ench.
        Instant occurred = BASE.minusSeconds(1);
        ContainerDepositRecord quested = new ContainerDepositRecord(
                EventIds.newId(), "deposit", occurred, occurred.plusSeconds(3600),
                Origin.player(), Source.player(UUID.randomUUID(), "Bob"),
                new BlockLocation(WORLD, "world", 1, 70, 1), "srv", "CHEST", "CHEST", 0, 1,
                new StoredItem(0, "PAPER", "blob", null, List.of(), List.of(),
                        "{quest:\"deliver_letter\"}"),
                null);
        store.save(List.of(quested, deposit(2))); // deposit(2): plain diamond, no tags

        QueryPredicate byTags = new QueryPredicate.Eq("beforeItem.tags",
                Pattern.compile(Pattern.quote("deliver_letter"), Pattern.CASE_INSENSITIVE));
        assertThat(store.query(request(List.of(byTags))).records())
                .singleElement()
                .satisfies(r -> assertThat(r.event()).isEqualTo("deposit"));

        QueryPredicate miss = new QueryPredicate.Eq("beforeItem.tags",
                Pattern.compile(Pattern.quote("nonexistent"), Pattern.CASE_INSENSITIVE));
        assertThat(store.query(request(List.of(miss))).records()).isEmpty();
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
        // stone/air block-data, server, origin/source kinds - a small fixed
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

    @Test
    void perEventRetentionPrunesEachTypeAtItsOwnHorizon() throws SQLException {
        // #181: break expires after 100s, say is kept forever, everything else
        // (deposit) inherits the 3600s default. A 500s-old row of each type:
        // only the break is past its horizon.
        store.close();
        RetentionPolicy policy = new RetentionPolicy(RETENTION, Map.of(
                "break", 100L, "say", RetentionPolicy.NEVER_SECONDS));
        store = new SqliteRecordStore(dir.resolve("spyglass.db"), false, policy);

        Instant old = BASE.minusSeconds(500);
        BlockBreakRecord oldBreak = breakAt(UUID.randomUUID(), "Old", 1, 500,
                simple(Material.STONE, "minecraft:stone"), simple(Material.AIR, "minecraft:air"));
        BlockBreakRecord recentBreak = breakAt(UUID.randomUUID(), "New", 2, 50,
                simple(Material.STONE, "minecraft:stone"), simple(Material.AIR, "minecraft:air"));
        ChatRecord oldSay = new ChatRecord(EventIds.newId(), "say", old, old.plusSeconds(3600),
                Origin.player(), Source.player(UUID.randomUUID(), "Dan"),
                new BlockLocation(WORLD, "world", 0, 64, 0), "srv", null,
                "hello", List.of(), Map.of());
        ContainerDepositRecord oldDeposit = deposit(500);
        store.save(List.of(oldBreak, recentBreak, oldSay, oldDeposit));
        assertThat(rawCount("records")).isEqualTo(4L);

        long pruned = store.pruneExpired();
        assertThat(pruned)
                .as("only the 500s-old break exceeds its 100s per-event retention")
                .isEqualTo(1L);

        List<UUID> surviving = store.query(request(List.of())).records().stream()
                .map(EventRecord::id).toList();
        assertThat(surviving)
                .as("break past its 100s horizon is pruned; recent break, never-say, "
                        + "and default-deposit survive")
                .doesNotContain(oldBreak.id())
                .contains(recentBreak.id(), oldSay.id(), oldDeposit.id());

        // Column-stored block events reconstruct their expiry per type on read.
        EventRecord readBreak = store.query(request(List.of())).records().stream()
                .filter(r -> r.id().equals(recentBreak.id())).findFirst().orElseThrow();
        assertThat(readBreak.expiresAt())
                .as("a column-stored break reconstructs expiry as occurred + its 100s retention")
                .isEqualTo(recentBreak.occurred().plusSeconds(100L));
        // Blob-stored events (chat) carry the write-time expiry inside the blob,
        // so SQLite reads that back rather than the per-type horizon. Deletion is
        // still per-type (the never-say survived the prune above); only the
        // displayed expiresAt differs. ClickHouse/Mongo store expires_at per type
        // and have no such gap.
        EventRecord readSay = store.query(request(List.of())).records().stream()
                .filter(r -> r.id().equals(oldSay.id())).findFirst().orElseThrow();
        assertThat(readSay.expiresAt())
                .as("blob-stored say keeps its write-time expiry on read (prune still per-type)")
                .isEqualTo(old.plusSeconds(3600));
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
