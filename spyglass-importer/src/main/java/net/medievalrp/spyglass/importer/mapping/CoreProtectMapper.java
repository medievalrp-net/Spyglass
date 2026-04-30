package net.medievalrp.spyglass.importer.mapping;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.medievalrp.spyglass.api.event.BlockBreakRecord;
import net.medievalrp.spyglass.api.event.BlockPlaceRecord;
import net.medievalrp.spyglass.api.event.BlockSnapshot;
import net.medievalrp.spyglass.api.event.BlockUseRecord;
import net.medievalrp.spyglass.api.event.ChatRecord;
import net.medievalrp.spyglass.api.event.CommandRecord;
import net.medievalrp.spyglass.api.event.ContainerDepositRecord;
import net.medievalrp.spyglass.api.event.ContainerWithdrawRecord;
import net.medievalrp.spyglass.api.event.EntityDeathRecord;
import net.medievalrp.spyglass.api.event.EventRecord;
import net.medievalrp.spyglass.api.event.ItemDropRecord;
import net.medievalrp.spyglass.api.event.ItemPickupRecord;
import net.medievalrp.spyglass.api.event.JoinRecord;
import net.medievalrp.spyglass.api.event.Origin;
import net.medievalrp.spyglass.api.event.QuitRecord;
import net.medievalrp.spyglass.api.event.Source;
import net.medievalrp.spyglass.api.event.StoredItem;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.importer.source.CoreProtectBlockRow;
import net.medievalrp.spyglass.importer.source.CoreProtectChatRow;
import net.medievalrp.spyglass.importer.source.CoreProtectContainerRow;
import net.medievalrp.spyglass.importer.source.CoreProtectItemRow;
import net.medievalrp.spyglass.importer.source.CoreProtectSessionRow;
import org.bukkit.Material;
import org.jetbrains.annotations.Nullable;

/**
 * Maps CoreProtect rows to Spyglass {@link EventRecord} subtypes. One
 * method per source table; each follows the same skeleton:
 *
 * <ol>
 *   <li>Resolve world UUID; skip {@code UNKNOWN_WORLD} if missing.</li>
 *   <li>Build {@link Source} / {@link Origin} from the player or the
 *       environmental pseudo-name.</li>
 *   <li>Skip {@code MISSING_PLAYER_UUID} for player rows with no UUID
 *       AND no environmental fallback.</li>
 *   <li>Construct the appropriate record type with deterministic id.</li>
 * </ol>
 *
 * <h2>Kill events</h2>
 *
 * {@code co_block.action == 3} is overloaded for kills (player kills
 * something or someone). These rows map to {@link EntityDeathRecord}
 * with the killer in {@code source} and the victim in
 * {@code entityType} / {@code entityId}. CoreProtect doesn't track the
 * damage cause, so {@code damageCause} is null on imported rows.
 */
public final class CoreProtectMapper {

    public enum SkipReason {
        MISSING_PLAYER_UUID,
        UNKNOWN_ACTION,
        UNKNOWN_WORLD,
        MISSING_VICTIM
    }

    public enum Provenance { PLAYER, ENVIRONMENT }

    public record Outcome(@Nullable EventRecord record,
                          @Nullable SkipReason skipReason,
                          @Nullable Provenance provenance) {

        public static Outcome ok(EventRecord r, Provenance p) {
            return new Outcome(r, null, p);
        }

        public static Outcome skip(SkipReason r) {
            return new Outcome(null, r, null);
        }
    }

    private final MappingContext ctx;

    public CoreProtectMapper(MappingContext ctx) {
        this.ctx = ctx;
    }

    // ===== co_block ============================================

    public Outcome mapBlock(CoreProtectBlockRow row) {
        UUID worldId = ctx.worldMap().get(row.worldName());
        if (worldId == null) return Outcome.skip(SkipReason.UNKNOWN_WORLD);

        SourceAttribution attribution = attribute(row.playerName(), row.playerUuid());
        if (attribution == null) return Outcome.skip(SkipReason.MISSING_PLAYER_UUID);

        UUID id = DeterministicId.forRow("co_block", row.rowid());
        Instant occurred = Instant.ofEpochSecond(row.timeEpochSeconds());
        Instant expiresAt = ctx.expiresAt();
        BlockLocation location = new BlockLocation(worldId, row.worldName(),
                row.x(), row.y(), row.z());

        // Action 3 (kill) gets its own record type.
        if (row.action() == 3) {
            return mapKill(row, id, occurred, expiresAt,
                    attribution, location);
        }

        Material material = Materials.resolve(row.materialName());
        Material safeMaterial = material != null ? material : Material.AIR;
        String blockData = Materials.coalesceBlockData(row.blockData(), safeMaterial);
        BlockSnapshot blockSnapshot = new BlockSnapshot(
                safeMaterial, blockData,
                List.of(), List.of(), List.of(), List.of(), null);
        BlockSnapshot airSnapshot = airSnapshot();
        String target = safeMaterial.name();

        return switch (row.action()) {
            case 0 -> Outcome.ok(new BlockBreakRecord(
                    id, "break", occurred, expiresAt,
                    attribution.origin(), attribution.source(), location,
                    ctx.serverName(), target, blockSnapshot, airSnapshot),
                    attribution.provenance());
            case 1 -> Outcome.ok(new BlockPlaceRecord(
                    id, "place", occurred, expiresAt,
                    attribution.origin(), attribution.source(), location,
                    ctx.serverName(), target, airSnapshot, blockSnapshot),
                    attribution.provenance());
            case 2 -> Outcome.ok(new BlockUseRecord(
                    id, "use", occurred, expiresAt,
                    attribution.origin(), attribution.source(), location,
                    ctx.serverName(), target),
                    attribution.provenance());
            default -> Outcome.skip(SkipReason.UNKNOWN_ACTION);
        };
    }

    private Outcome mapKill(CoreProtectBlockRow row, UUID id, Instant occurred,
                            Instant expiresAt, SourceAttribution attribution,
                            BlockLocation location) {
        boolean isPlayerKill = row.killedPlayerName() != null;
        String victimType = isPlayerKill ? "minecraft:player" : row.killedEntityType();
        if (victimType == null) return Outcome.skip(SkipReason.MISSING_VICTIM);
        UUID victimId = isPlayerKill ? row.killedPlayerUuid() : null;
        // killer type: action=3 is always player-attributed in CoreProtect.
        String killerType = "minecraft:player";
        return Outcome.ok(new EntityDeathRecord(
                id, "death", occurred, expiresAt,
                attribution.origin(), attribution.source(), location,
                ctx.serverName(),
                victimType, victimType, victimId, killerType, null, null),
                attribution.provenance());
    }

    // ===== co_session ==========================================

    public Outcome mapSession(CoreProtectSessionRow row) {
        UUID worldId = ctx.worldMap().get(row.worldName());
        if (worldId == null) return Outcome.skip(SkipReason.UNKNOWN_WORLD);
        if (row.playerUuid() == null) return Outcome.skip(SkipReason.MISSING_PLAYER_UUID);

        UUID id = DeterministicId.forRow("co_session", row.rowid());
        Instant occurred = Instant.ofEpochSecond(row.timeEpochSeconds());
        Instant expiresAt = ctx.expiresAt();
        Source source = Source.player(row.playerUuid(), row.playerName());
        BlockLocation location = new BlockLocation(worldId, row.worldName(),
                row.x(), row.y(), row.z());

        return switch (row.action()) {
            case 1 -> Outcome.ok(new JoinRecord(
                    id, "join", occurred, expiresAt,
                    ctx.importOrigin(), source, location,
                    ctx.serverName(), row.playerName(), null /* no IP in CoreProtect */),
                    Provenance.PLAYER);
            case 0 -> Outcome.ok(new QuitRecord(
                    id, "quit", occurred, expiresAt,
                    ctx.importOrigin(), source, location,
                    ctx.serverName(), row.playerName()),
                    Provenance.PLAYER);
            default -> Outcome.skip(SkipReason.UNKNOWN_ACTION);
        };
    }

    // ===== co_chat / co_command ================================

    public Outcome mapChat(CoreProtectChatRow row) {
        return mapChatLike(row, "co_chat", "say", true);
    }

    public Outcome mapCommand(CoreProtectChatRow row) {
        return mapChatLike(row, "co_command", "command", false);
    }

    private Outcome mapChatLike(CoreProtectChatRow row, String table,
                                String event, boolean isChat) {
        UUID worldId = ctx.worldMap().get(row.worldName());
        if (worldId == null) return Outcome.skip(SkipReason.UNKNOWN_WORLD);
        if (row.playerUuid() == null) return Outcome.skip(SkipReason.MISSING_PLAYER_UUID);

        UUID id = DeterministicId.forRow(table, row.rowid());
        Instant occurred = Instant.ofEpochSecond(row.timeEpochSeconds());
        Instant expiresAt = ctx.expiresAt();
        Source source = Source.player(row.playerUuid(), row.playerName());
        BlockLocation location = new BlockLocation(worldId, row.worldName(),
                row.x(), row.y(), row.z());

        EventRecord record = isChat
                ? new ChatRecord(id, event, occurred, expiresAt,
                    ctx.importOrigin(), source, location, ctx.serverName(),
                    row.playerName(), row.message(), List.<UUID>of(), Map.of())
                : new CommandRecord(id, event, occurred, expiresAt,
                    ctx.importOrigin(), source, location, ctx.serverName(),
                    row.playerName(), row.message());
        return Outcome.ok(record, Provenance.PLAYER);
    }

    // ===== co_container ========================================

    public Outcome mapContainer(CoreProtectContainerRow row) {
        UUID worldId = ctx.worldMap().get(row.worldName());
        if (worldId == null) return Outcome.skip(SkipReason.UNKNOWN_WORLD);

        SourceAttribution attribution = attribute(row.playerName(), row.playerUuid());
        if (attribution == null) return Outcome.skip(SkipReason.MISSING_PLAYER_UUID);

        UUID id = DeterministicId.forRow("co_container", row.rowid());
        Instant occurred = Instant.ofEpochSecond(row.timeEpochSeconds());
        Instant expiresAt = ctx.expiresAt();
        BlockLocation location = new BlockLocation(worldId, row.worldName(),
                row.x(), row.y(), row.z());

        Material material = Materials.resolve(row.materialName());
        Material safeMaterial = material != null ? material : Material.AIR;
        StoredItem item = buildStoredItem(safeMaterial, row.amount(), row.metadata());
        StoredItem empty = new StoredItem(-1, "AIR", null);
        String containerType = "UNKNOWN"; // CoreProtect doesn't track it
        String target = safeMaterial.name();

        return switch (row.action()) {
            // 0 = removed (withdraw): beforeItem = the item, afterItem = empty
            case 0 -> Outcome.ok(new ContainerWithdrawRecord(
                    id, "withdraw", occurred, expiresAt,
                    attribution.origin(), attribution.source(), location,
                    ctx.serverName(), target, containerType,
                    -1, row.amount(), item, empty),
                    attribution.provenance());
            // 1 = added (deposit): beforeItem = empty, afterItem = the item
            case 1 -> Outcome.ok(new ContainerDepositRecord(
                    id, "deposit", occurred, expiresAt,
                    attribution.origin(), attribution.source(), location,
                    ctx.serverName(), target, containerType,
                    -1, row.amount(), empty, item),
                    attribution.provenance());
            default -> Outcome.skip(SkipReason.UNKNOWN_ACTION);
        };
    }

    // ===== co_item =============================================

    public Outcome mapItem(CoreProtectItemRow row) {
        UUID worldId = ctx.worldMap().get(row.worldName());
        if (worldId == null) return Outcome.skip(SkipReason.UNKNOWN_WORLD);
        if (row.playerUuid() == null) return Outcome.skip(SkipReason.MISSING_PLAYER_UUID);

        // Spyglass models drop (CP action 2) and pickup (CP action 3)
        // as first-class events. The other 11 CoreProtect item action
        // codes have no clean counterpart in Spyglass's sealed
        // EventRecord hierarchy and would need new record types added
        // to spyglass-api before the importer could carry them:
        //
        //   0  ITEM_REMOVE        (player ender-context remove)
        //   1  ITEM_ADD           (player ender-context add)
        //   4  ITEM_REMOVE_ENDER  (ender chest remove)
        //   5  ITEM_ADD_ENDER     (ender chest add)
        //   6  ITEM_THROW         (egg / snowball / projectile)
        //   7  ITEM_SHOOT         (arrow / projectile)
        //   8  ITEM_BREAK         (durability ran out)
        //   9  ITEM_DESTROY       (cactus / lava / void)
        //  10  ITEM_CREATE        (crafting result, mod-driven)
        //  11  ITEM_SELL
        //  12  ITEM_BUY
        //
        // For now: skip with UNKNOWN_ACTION + record the action code
        // so the operator sees in the import summary which CP actions
        // their dataset uses. When new Spyglass record types land,
        // add the cases here.
        if (row.action() != 2 && row.action() != 3) {
            return Outcome.skip(SkipReason.UNKNOWN_ACTION);
        }

        UUID id = DeterministicId.forRow("co_item", row.rowid());
        Instant occurred = Instant.ofEpochSecond(row.timeEpochSeconds());
        Instant expiresAt = ctx.expiresAt();
        Source source = Source.player(row.playerUuid(), row.playerName());
        BlockLocation location = new BlockLocation(worldId, row.worldName(),
                row.x(), row.y(), row.z());

        Material material = Materials.resolve(row.materialName());
        Material safeMaterial = material != null ? material : Material.AIR;
        StoredItem item = buildStoredItem(safeMaterial, row.amount(), row.metadata());
        String target = safeMaterial.name();

        if (row.action() == 2) {
            return Outcome.ok(new ItemDropRecord(
                    id, "drop", occurred, expiresAt,
                    ctx.importOrigin(), source, location,
                    ctx.serverName(), target, row.amount(), item),
                    Provenance.PLAYER);
        }
        return Outcome.ok(new ItemPickupRecord(
                id, "pickup", occurred, expiresAt,
                ctx.importOrigin(), source, location,
                ctx.serverName(), target, row.amount(), item),
                Provenance.PLAYER);
    }

    // ===== shared helpers ======================================

    private record SourceAttribution(Source source, Origin origin, Provenance provenance) {}

    /**
     * Resolve a CoreProtect player row to a Source/Origin pair, or
     * {@code null} if the row has neither a player UUID nor an
     * environmental pseudo-name.
     */
    @Nullable
    private SourceAttribution attribute(@Nullable String playerName,
                                        @Nullable UUID playerUuid) {
        String envKind = EnvironmentalCauses.kindOf(playerName);
        if (envKind != null) {
            return new SourceAttribution(
                    Source.environment(envKind),
                    Origin.environment(envKind),
                    Provenance.ENVIRONMENT);
        }
        if (playerUuid == null) return null;
        return new SourceAttribution(
                Source.player(playerUuid, playerName),
                ctx.importOrigin(),
                Provenance.PLAYER);
    }

    private static BlockSnapshot airSnapshot() {
        return new BlockSnapshot(Material.AIR, "minecraft:air",
                List.of(), List.of(), List.of(), List.of(), null);
    }

    /**
     * Build a {@link StoredItem} with name/lore/enchant projections
     * pulled from the meta blob when present.
     */
    private static StoredItem buildStoredItem(Material material, int amount,
                                              @Nullable byte[] metaBlob) {
        Map<String, Object> headline = ItemMetaDecoder.decodeHeadlineMap(metaBlob);
        ItemMetaDecoder.Projections proj = ItemMetaDecoder.projectionsFrom(headline);
        // We don't reconstruct ItemMeta here — that path needs a fully
        // initialised Bukkit runtime, which the importer doesn't have.
        // Operators get the searchable projections; full ItemStack
        // restore will be a follow-up if the data is needed.
        return new StoredItem(-1, material.name(), null,
                proj.displayName(), proj.lore(), proj.enchants());
    }
}
