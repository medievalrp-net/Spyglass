package net.medievalrp.spyglass.importer.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import net.medievalrp.spyglass.api.event.BlockBreakRecord;
import net.medievalrp.spyglass.api.event.BlockPlaceRecord;
import net.medievalrp.spyglass.api.event.BlockUseRecord;
import net.medievalrp.spyglass.api.event.ChatRecord;
import net.medievalrp.spyglass.api.event.CommandRecord;
import net.medievalrp.spyglass.api.event.ContainerDepositRecord;
import net.medievalrp.spyglass.api.event.ContainerWithdrawRecord;
import net.medievalrp.spyglass.api.event.EntityDeathRecord;
import net.medievalrp.spyglass.api.event.ItemDropRecord;
import net.medievalrp.spyglass.api.event.ItemPickupRecord;
import net.medievalrp.spyglass.api.event.JoinRecord;
import net.medievalrp.spyglass.api.event.Origin;
import net.medievalrp.spyglass.api.event.QuitRecord;
import net.medievalrp.spyglass.api.event.Source;
import net.medievalrp.spyglass.importer.mapping.CoreProtectMapper.Outcome;
import net.medievalrp.spyglass.importer.mapping.CoreProtectMapper.Provenance;
import net.medievalrp.spyglass.importer.mapping.CoreProtectMapper.SkipReason;
import net.medievalrp.spyglass.importer.source.CoreProtectBlockRow;
import net.medievalrp.spyglass.importer.source.CoreProtectChatRow;
import net.medievalrp.spyglass.importer.source.CoreProtectContainerRow;
import net.medievalrp.spyglass.importer.source.CoreProtectItemRow;
import net.medievalrp.spyglass.importer.source.CoreProtectSessionRow;
import net.medievalrp.spyglass.importer.world.WorldMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CoreProtectMapperTest {

    private static final UUID WORLD_UUID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ALICE_UUID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID BOB_UUID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final Instant IMPORT_AT = Instant.parse("2026-04-29T12:00:00Z");

    private CoreProtectMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        // WorldMap doesn't expose a public constructor; build one via a
        // tmp directory with a uid.dat. Cheap one-off per test.
        java.nio.file.Path tmp = java.nio.file.Files.createTempDirectory("mapper-test-");
        java.nio.file.Path worldDir = tmp.resolve("world");
        java.nio.file.Files.createDirectories(worldDir);
        java.io.DataOutputStream out = new java.io.DataOutputStream(
                java.nio.file.Files.newOutputStream(worldDir.resolve("uid.dat")));
        out.writeLong(WORLD_UUID.getMostSignificantBits());
        out.writeLong(WORLD_UUID.getLeastSignificantBits());
        out.close();
        WorldMap worldMap = WorldMap.resolve(tmp, java.util.List.of("world"));
        MappingContext ctx = new MappingContext(worldMap, "test-server",
                Duration.ofDays(30), IMPORT_AT);
        mapper = new CoreProtectMapper(ctx);
    }

    // ===== co_block ============================================

    @Test
    void block_action_0_maps_to_break_record() {
        CoreProtectBlockRow row = block(1L, "world", 100, 64, 100,
                "minecraft:stone", null, 0, "Alice", ALICE_UUID, null, null, null);
        Outcome out = mapper.mapBlock(row);
        assertThat(out.record()).isInstanceOf(BlockBreakRecord.class);
        assertThat(out.provenance()).isEqualTo(Provenance.PLAYER);
        BlockBreakRecord br = (BlockBreakRecord) out.record();
        assertThat(br.event()).isEqualTo("break");
        assertThat(br.target()).isEqualTo("STONE");
        assertThat(br.source().playerName()).isEqualTo("Alice");
        assertThat(br.origin().kind()).isEqualTo(Origin.PLUGIN);
        assertThat(br.origin().detail()).isEqualTo("coreprotect-import");
    }

    @Test
    void block_action_1_maps_to_place_record() {
        CoreProtectBlockRow row = block(2L, "world", 1, 2, 3,
                "minecraft:oak_log", "minecraft:oak_log[axis=y]", 1,
                "Alice", ALICE_UUID, null, null, null);
        Outcome out = mapper.mapBlock(row);
        assertThat(out.record()).isInstanceOf(BlockPlaceRecord.class);
        BlockPlaceRecord pr = (BlockPlaceRecord) out.record();
        assertThat(pr.target()).isEqualTo("OAK_LOG");
        assertThat(pr.newBlock().blockData()).isEqualTo("minecraft:oak_log[axis=y]");
    }

    @Test
    void block_action_2_maps_to_use_record() {
        CoreProtectBlockRow row = block(3L, "world", 0, 0, 0,
                "minecraft:lever", null, 2, "Alice", ALICE_UUID, null, null, null);
        Outcome out = mapper.mapBlock(row);
        assertThat(out.record()).isInstanceOf(BlockUseRecord.class);
        assertThat(out.record().event()).isEqualTo("use");
    }

    @Test
    void block_action_3_with_entity_maps_to_death_record() {
        CoreProtectBlockRow row = block(4L, "world", 10, 70, 10,
                null, null, 3, "Alice", ALICE_UUID, "cow", null, null);
        Outcome out = mapper.mapBlock(row);
        assertThat(out.record()).isInstanceOf(EntityDeathRecord.class);
        EntityDeathRecord d = (EntityDeathRecord) out.record();
        assertThat(d.entityType()).isEqualTo("cow");
        assertThat(d.entityId()).isNull();          // entities have no UUID in CP
        assertThat(d.killerType()).isEqualTo("minecraft:player");
        assertThat(d.source().playerId()).isEqualTo(ALICE_UUID);
    }

    @Test
    void block_action_3_with_player_victim_uses_player_uuid() {
        CoreProtectBlockRow row = block(5L, "world", 10, 70, 10,
                null, null, 3, "Alice", ALICE_UUID, null, "Bob", BOB_UUID);
        Outcome out = mapper.mapBlock(row);
        EntityDeathRecord d = (EntityDeathRecord) out.record();
        assertThat(d.entityType()).isEqualTo("minecraft:player");
        assertThat(d.entityId()).isEqualTo(BOB_UUID);
    }

    @Test
    void environmental_pseudo_player_uses_environment_source() {
        // CoreProtect uses #water/#decay/#piston etc. as pseudo-players;
        // imported as Source.environment + Origin.environment, NOT skipped.
        CoreProtectBlockRow row = block(6L, "world", 5, 5, 5,
                "minecraft:dirt", null, 0, "#water", null, null, null, null);
        Outcome out = mapper.mapBlock(row);
        assertThat(out.record()).isInstanceOf(BlockBreakRecord.class);
        assertThat(out.provenance()).isEqualTo(Provenance.ENVIRONMENT);
        BlockBreakRecord br = (BlockBreakRecord) out.record();
        assertThat(br.source().kind()).isEqualTo(Source.ENVIRONMENT);
        assertThat(br.source().description()).isEqualTo("water");
        assertThat(br.origin().kind()).isEqualTo(Origin.ENVIRONMENT);
        assertThat(br.origin().detail()).isEqualTo("water");
    }

    @Test
    void block_with_missing_player_uuid_skips() {
        CoreProtectBlockRow row = block(7L, "world", 0, 0, 0,
                "minecraft:stone", null, 0, "AncientPlayer", null, null, null, null);
        Outcome out = mapper.mapBlock(row);
        assertThat(out.record()).isNull();
        assertThat(out.skipReason()).isEqualTo(SkipReason.MISSING_PLAYER_UUID);
    }

    @Test
    void block_with_unknown_world_skips() {
        CoreProtectBlockRow row = block(8L, "the_nether", 0, 0, 0,
                "minecraft:stone", null, 0, "Alice", ALICE_UUID, null, null, null);
        Outcome out = mapper.mapBlock(row);
        assertThat(out.record()).isNull();
        assertThat(out.skipReason()).isEqualTo(SkipReason.UNKNOWN_WORLD);
    }

    // ===== co_session ==========================================

    @Test
    void session_action_1_maps_to_join_with_null_address() {
        CoreProtectSessionRow row = new CoreProtectSessionRow(
                10L, 1700000000L, "world", 0, 70, 0, 1, "Alice", ALICE_UUID);
        Outcome out = mapper.mapSession(row);
        assertThat(out.record()).isInstanceOf(JoinRecord.class);
        JoinRecord jr = (JoinRecord) out.record();
        assertThat(jr.address()).isNull();   // CP doesn't carry IPs
        assertThat(jr.source().playerName()).isEqualTo("Alice");
    }

    @Test
    void session_action_0_maps_to_quit() {
        CoreProtectSessionRow row = new CoreProtectSessionRow(
                11L, 1700000900L, "world", 0, 70, 0, 0, "Alice", ALICE_UUID);
        Outcome out = mapper.mapSession(row);
        assertThat(out.record()).isInstanceOf(QuitRecord.class);
    }

    // ===== co_chat / co_command ================================

    @Test
    void chat_row_maps_to_chat_record() {
        CoreProtectChatRow row = new CoreProtectChatRow(
                20L, 1700000100L, "world", 0, 70, 0, "hello", "Alice", ALICE_UUID);
        Outcome out = mapper.mapChat(row);
        assertThat(out.record()).isInstanceOf(ChatRecord.class);
        ChatRecord c = (ChatRecord) out.record();
        assertThat(c.message()).isEqualTo("hello");
        assertThat(c.event()).isEqualTo("say");
    }

    @Test
    void command_row_maps_to_command_record() {
        CoreProtectChatRow row = new CoreProtectChatRow(
                21L, 1700000200L, "world", 0, 70, 0, "/help", "Alice", ALICE_UUID);
        Outcome out = mapper.mapCommand(row);
        assertThat(out.record()).isInstanceOf(CommandRecord.class);
        CommandRecord c = (CommandRecord) out.record();
        assertThat(c.commandLine()).isEqualTo("/help");
    }

    // ===== co_container ========================================

    @Test
    void container_action_1_is_deposit_with_after_item_populated() {
        CoreProtectContainerRow row = new CoreProtectContainerRow(
                30L, 1700000300L, "world", 0, 70, 0,
                "minecraft:bread", 8, 1, false, null, "Alice", ALICE_UUID);
        Outcome out = mapper.mapContainer(row);
        assertThat(out.record()).isInstanceOf(ContainerDepositRecord.class);
        ContainerDepositRecord dep = (ContainerDepositRecord) out.record();
        assertThat(dep.afterItem().material()).isEqualTo("BREAD");
        assertThat(dep.amount()).isEqualTo(8);
    }

    @Test
    void container_action_0_is_withdraw_with_before_item_populated() {
        CoreProtectContainerRow row = new CoreProtectContainerRow(
                31L, 1700000310L, "world", 0, 70, 0,
                "minecraft:bread", 3, 0, false, null, "Alice", ALICE_UUID);
        Outcome out = mapper.mapContainer(row);
        assertThat(out.record()).isInstanceOf(ContainerWithdrawRecord.class);
        ContainerWithdrawRecord w = (ContainerWithdrawRecord) out.record();
        assertThat(w.beforeItem().material()).isEqualTo("BREAD");
        assertThat(w.amount()).isEqualTo(3);
    }

    // ===== co_item =============================================

    @Test
    void item_action_2_is_drop() {
        CoreProtectItemRow row = new CoreProtectItemRow(
                40L, 1700000400L, "world", 0, 70, 0,
                "minecraft:bread", 5, 2, false, null, "Alice", ALICE_UUID);
        Outcome out = mapper.mapItem(row);
        assertThat(out.record()).isInstanceOf(ItemDropRecord.class);
    }

    @Test
    void item_action_3_is_pickup() {
        CoreProtectItemRow row = new CoreProtectItemRow(
                41L, 1700000410L, "world", 0, 70, 0,
                "minecraft:bread", 5, 3, false, null, "Bob", BOB_UUID);
        Outcome out = mapper.mapItem(row);
        assertThat(out.record()).isInstanceOf(ItemPickupRecord.class);
    }

    @Test
    void item_action_8_durability_break_skips_with_unknown_action() {
        CoreProtectItemRow row = new CoreProtectItemRow(
                42L, 1700000420L, "world", 0, 70, 0,
                "minecraft:diamond_pickaxe", 1, 8, false, null, "Alice", ALICE_UUID);
        Outcome out = mapper.mapItem(row);
        assertThat(out.record()).isNull();
        assertThat(out.skipReason()).isEqualTo(SkipReason.UNKNOWN_ACTION);
    }

    // ===== expires_at policy ===================================

    @Test
    void expires_at_is_relative_to_import_time_not_event_time() {
        // Critical contract: TTL on the CH table is on expires_at;
        // computing it from `occurred + retention` would evict
        // historical CoreProtect data the moment it lands. Test pins
        // the import-relative semantics.
        long ancientEvent = 1_577_836_800L; // 2020-01-01
        CoreProtectBlockRow row = block(50L, "world", 0, 0, 0,
                "minecraft:stone", null, 0, "Alice", ALICE_UUID, null, null, null);
        // override timeEpochSeconds via builder
        row = new CoreProtectBlockRow(row.rowid(), ancientEvent,
                row.worldName(), row.x(), row.y(), row.z(),
                row.materialName(), row.blockData(), row.action(),
                row.rolledBack(), row.playerName(), row.playerUuid(),
                row.killedEntityType(), row.killedPlayerName(), row.killedPlayerUuid());
        Outcome out = mapper.mapBlock(row);
        assertThat(out.record().expiresAt())
                .isEqualTo(IMPORT_AT.plus(Duration.ofDays(30)));
        // And `occurred` reflects the original event timestamp,
        // separately, so the historical data is queryable by date.
        assertThat(out.record().occurred())
                .isEqualTo(Instant.ofEpochSecond(ancientEvent));
    }

    // ===== helpers =============================================

    private static CoreProtectBlockRow block(long rowid, String worldName,
                                             int x, int y, int z,
                                             String material, String blockData,
                                             int action, String playerName,
                                             UUID playerUuid,
                                             String killedEntity,
                                             String killedPlayer,
                                             UUID killedPlayerUuid) {
        return new CoreProtectBlockRow(rowid, 1700000000L, worldName,
                x, y, z, material, blockData, action, false,
                playerName, playerUuid,
                killedEntity, killedPlayer, killedPlayerUuid);
    }
}
