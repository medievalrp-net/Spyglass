package net.medievalrp.spyglass.plugin.migration;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;
import net.medievalrp.spyglass.api.event.BlockBreakRecord;
import net.medievalrp.spyglass.api.event.BlockPlaceRecord;
import net.medievalrp.spyglass.api.event.BlockSnapshot;
import net.medievalrp.spyglass.api.event.ChatRecord;
import net.medievalrp.spyglass.api.event.CommandRecord;
import net.medievalrp.spyglass.api.event.ContainerDepositRecord;
import net.medievalrp.spyglass.api.event.ContainerWithdrawRecord;
import net.medievalrp.spyglass.api.event.EntityDeathRecord;
import net.medievalrp.spyglass.api.event.EntityHitRecord;
import net.medievalrp.spyglass.api.event.EntityMountRecord;
import net.medievalrp.spyglass.api.event.EventRecord;
import net.medievalrp.spyglass.api.event.ItemDropRecord;
import net.medievalrp.spyglass.api.event.ItemPickupRecord;
import net.medievalrp.spyglass.api.event.JoinRecord;
import net.medievalrp.spyglass.api.event.Origin;
import net.medievalrp.spyglass.api.event.QuitRecord;
import net.medievalrp.spyglass.api.event.RecordContext;
import net.medievalrp.spyglass.api.event.Source;
import net.medievalrp.spyglass.api.event.StoredItem;
import net.medievalrp.spyglass.api.event.TeleportRecord;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.plugin.util.BlockSnapshots;
import org.bson.Document;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class V1ToV2Translator {

    private static final int SCHEMA_VERSION = 1;

    private final V1ItemDecoder itemDecoder;
    private final WorldNameLookup worldNameLookup;
    private final Logger logger;

    public V1ToV2Translator(V1ItemDecoder itemDecoder, WorldNameLookup worldNameLookup, Logger logger) {
        this.itemDecoder = itemDecoder;
        this.worldNameLookup = worldNameLookup;
        this.logger = logger;
    }

    public Result translate(Document document) {
        String event = document.getString(V1Schema.F_EVENT);
        if (event == null || event.isBlank()) {
            return Result.skipped("missing-event");
        }
        String lower = event.toLowerCase(Locale.ROOT);
        if (V1Schema.DEFERRED_EVENTS.contains(lower)) {
            return Result.deferred(lower);
        }
        try {
            EventRecord record = switch (lower) {
                case "break" -> blockBreak(document, lower);
                case "decay" -> blockBreak(document, lower);
                case "place" -> blockPlace(document, lower);
                case "form" -> blockPlace(document, lower);
                case "grow" -> blockPlace(document, lower);
                case "ignite" -> blockPlace(document, lower);
                case "say" -> chat(document);
                case "command" -> command(document);
                case "join" -> join(document);
                case "quit" -> quit(document);
                case "deposit" -> containerDeposit(document);
                case "withdraw" -> containerWithdraw(document);
                case "drop" -> itemDrop(document);
                case "pickup" -> itemPickup(document);
                case "teleport" -> teleport(document);
                case "death" -> entityDeath(document);
                case "hit" -> entityHit(document, false);
                case "shot" -> entityHit(document, true);
                case "mount" -> entityMount(document, false);
                case "dismount" -> entityMount(document, true);
                default -> null;
            };
            if (record == null) {
                return Result.unknown(lower);
            }
            return Result.translated(record);
        } catch (RuntimeException ex) {
            if (logger != null) {
                logger.fine("migration: failed to translate " + lower + ": " + ex.getMessage());
            }
            return Result.failed(lower, ex.getMessage());
        }
    }

    private RecordContext contextFor(Document doc, String event, boolean requireLocation) {
        BlockLocation location = requireLocation ? requireLocation(doc) : location(doc, false);
        return new RecordContext(
                UUID.randomUUID(), SCHEMA_VERSION,
                occurred(doc), expires(doc),
                origin(doc, event, false), source(doc), location);
    }

    private BlockBreakRecord blockBreak(Document doc, String event) {
        RecordContext ctx = contextFor(doc, event, true);
        BlockSnapshot original = readSnapshot(doc.get(V1Schema.F_ORIGINAL_BLOCK, Document.class));
        BlockSnapshot next = readSnapshot(doc.get(V1Schema.F_NEW_BLOCK, Document.class));
        return BlockBreakRecord.of(ctx, event, readTarget(doc, original), original, next);
    }

    private BlockPlaceRecord blockPlace(Document doc, String event) {
        RecordContext ctx = contextFor(doc, event, true);
        BlockSnapshot original = readSnapshot(doc.get(V1Schema.F_ORIGINAL_BLOCK, Document.class));
        BlockSnapshot next = readSnapshot(doc.get(V1Schema.F_NEW_BLOCK, Document.class));
        return BlockPlaceRecord.of(ctx, event, readTarget(doc, next), original, next);
    }

    private ChatRecord chat(Document doc) {
        return ChatRecord.of(contextFor(doc, "say", false),
                readTarget(doc, null),
                optionalString(doc.getString(V1Schema.F_MESSAGE)),
                parseRecipients(doc.getString(V1Schema.F_RECIPIENT)));
    }

    private CommandRecord command(Document doc) {
        return CommandRecord.of(contextFor(doc, "command", false),
                readTarget(doc, null),
                optionalString(doc.getString(V1Schema.F_MESSAGE)));
    }

    private JoinRecord join(Document doc) {
        return JoinRecord.of(contextFor(doc, "join", false),
                readTarget(doc, null),
                optionalString(doc.getString(V1Schema.F_IP_ADDRESS)));
    }

    private QuitRecord quit(Document doc) {
        return QuitRecord.of(contextFor(doc, "quit", false), readTarget(doc, null));
    }

    private ContainerDepositRecord containerDeposit(Document doc) {
        SlotPair pair = readSlotPair(doc);
        return ContainerDepositRecord.of(contextFor(doc, "deposit", true), "deposit",
                readTarget(doc, null), containerType(doc),
                pair.slot(), pair.amount(), pair.before(), pair.after());
    }

    private ContainerWithdrawRecord containerWithdraw(Document doc) {
        SlotPair pair = readSlotPair(doc);
        return ContainerWithdrawRecord.of(contextFor(doc, "withdraw", true), "withdraw",
                readTarget(doc, null), containerType(doc),
                pair.slot(), pair.amount(), pair.before(), pair.after());
    }

    private ItemDropRecord itemDrop(Document doc) {
        return ItemDropRecord.of(contextFor(doc, "drop", true),
                readTarget(doc, null), itemAmount(doc), readItem(doc, 0));
    }

    private ItemPickupRecord itemPickup(Document doc) {
        return ItemPickupRecord.of(contextFor(doc, "pickup", true),
                readTarget(doc, null), itemAmount(doc), readItem(doc, 0));
    }

    private TeleportRecord teleport(Document doc) {
        RecordContext ctx = contextFor(doc, "teleport", true);
        BlockLocation from = readLocationSubdoc(doc.get(V1Schema.F_FROM, Document.class));
        BlockLocation to = readLocationSubdoc(doc.get(V1Schema.F_TO, Document.class));
        if (from == null) {
            from = ctx.location();
        }
        if (to == null) {
            to = ctx.location();
        }
        return TeleportRecord.of(ctx, readTarget(doc, null), from, to,
                optionalString(doc.getString(V1Schema.F_CAUSE_TYPE)));
    }

    private EntityDeathRecord entityDeath(Document doc) {
        return EntityDeathRecord.of(contextFor(doc, "death", true),
                readTarget(doc, null),
                optionalString(doc.getString(V1Schema.F_ENTITY_TYPE)),
                parseUuid(doc.getString(V1Schema.F_ENTITY_ID)),
                optionalString(doc.getString(V1Schema.F_CAUSE_TYPE)),
                optionalString(doc.getString(V1Schema.F_DAMAGE_CAUSE)),
                null);
    }

    private EntityHitRecord entityHit(Document doc, boolean projectile) {
        String event = projectile ? "shot" : "hit";
        String projectileType = optionalString(doc.getString(V1Schema.F_PROJECTILE));
        return EntityHitRecord.of(contextFor(doc, event, true), event,
                readTarget(doc, null),
                optionalString(doc.getString(V1Schema.F_VICTIM_TYPE)),
                parseUuid(doc.getString(V1Schema.F_VICTIM_ID)),
                readDouble(doc.get(V1Schema.F_DAMAGE)),
                projectile, projectileType);
    }

    private EntityMountRecord entityMount(Document doc, boolean dismount) {
        String event = dismount ? "dismount" : "mount";
        return EntityMountRecord.of(contextFor(doc, event, true), event,
                readTarget(doc, null),
                optionalString(doc.getString(V1Schema.F_MOUNT_TYPE)),
                parseUuid(doc.getString(V1Schema.F_MOUNT_ID)),
                dismount);
    }

    private BlockSnapshot readSnapshot(Document snapshotDoc) {
        if (snapshotDoc == null) {
            return BlockSnapshots.air();
        }
        String materialName = optionalString(snapshotDoc.getString(V1Schema.F_MATERIAL_TYPE));
        org.bukkit.Material material = BlockSnapshots.matchMaterial(materialName);
        String blockData = optionalString(snapshotDoc.getString(V1Schema.F_BLOCK_DATA));
        List<StoredItem> containerItems = readInventory(snapshotDoc.get(V1Schema.F_INVENTORY, Document.class));
        List<String> signFront = readStringList(snapshotDoc.get(V1Schema.F_SIGN_TEXT));
        List<String> signBack = List.of();
        List<String> bannerPatterns = readStringList(snapshotDoc.get(V1Schema.F_BANNER_PATTERNS));
        String jukeboxRecord = optionalString(snapshotDoc.getString(V1Schema.F_RECORD));
        return new BlockSnapshot(material, blockData == null ? "" : blockData,
                containerItems, signFront, signBack, bannerPatterns, jukeboxRecord);
    }

    private List<StoredItem> readInventory(Document inventory) {
        if (inventory == null || inventory.isEmpty()) {
            return List.of();
        }
        List<StoredItem> items = new ArrayList<>();
        for (Map.Entry<String, Object> entry : inventory.entrySet()) {
            int slot = parseSlot(entry.getKey());
            if (slot < 0) {
                continue;
            }
            Object value = entry.getValue();
            if (!(value instanceof Document itemDoc)) {
                continue;
            }
            Map<String, Object> asMap = toPlainMap(itemDoc);
            Optional<StoredItem> stored = itemDecoder.decode(slot, asMap);
            stored.ifPresent(items::add);
        }
        Collections.sort(items, (a, b) -> Integer.compare(a.slot(), b.slot()));
        return items;
    }

    private StoredItem readItem(Document doc, int slot) {
        Object value = doc.get(V1Schema.F_ITEMSTACK);
        if (value instanceof Document itemDoc) {
            Map<String, Object> map = toPlainMap(itemDoc);
            Optional<StoredItem> stored = itemDecoder.decode(slot, map);
            if (stored.isPresent()) {
                return stored.get();
            }
        }
        String target = doc.getString(V1Schema.F_TARGET);
        if (target != null && !target.isBlank()) {
            return new StoredItem(slot, target, null);
        }
        return null;
    }

    private int itemAmount(Document doc) {
        Object quantity = doc.get(V1Schema.F_QUANTITY);
        if (quantity instanceof Number number) {
            return number.intValue();
        }
        return 1;
    }

    private SlotPair readSlotPair(Document doc) {
        int slot = toInt(doc.get(V1Schema.F_SLOT), 0);
        Object inventory = doc.get(V1Schema.F_INVENTORY);
        Document before = null;
        Document after = null;
        if (inventory instanceof Document invDoc) {
            before = invDoc.get(V1Schema.F_BEFORE, Document.class);
            after = invDoc.get(V1Schema.F_AFTER, Document.class);
        } else {
            before = doc.get(V1Schema.F_BEFORE, Document.class);
            after = doc.get(V1Schema.F_AFTER, Document.class);
        }
        StoredItem beforeItem = readNestedItem(before, slot);
        StoredItem afterItem = readNestedItem(after, slot);
        int amount = itemAmount(doc);
        if (amount == 1 && beforeItem != null && afterItem == null) {
            amount = Math.max(1, inferAmount(before));
        } else if (amount == 1 && afterItem != null && beforeItem == null) {
            amount = Math.max(1, inferAmount(after));
        }
        return new SlotPair(slot, amount, beforeItem, afterItem);
    }

    private StoredItem readNestedItem(Document slotDoc, int slot) {
        if (slotDoc == null) {
            return null;
        }
        Object candidate = slotDoc.get(V1Schema.F_ITEMSTACK);
        if (candidate instanceof Document itemDoc) {
            Map<String, Object> map = toPlainMap(itemDoc);
            return itemDecoder.decode(slot, map).orElse(null);
        }
        Map<String, Object> map = toPlainMap(slotDoc);
        if (map.isEmpty()) {
            return null;
        }
        return itemDecoder.decode(slot, map).orElse(null);
    }

    private int inferAmount(Document slotDoc) {
        if (slotDoc == null) {
            return 1;
        }
        Object amount = slotDoc.get("amount");
        if (amount instanceof Number number) {
            return number.intValue();
        }
        Document itemDoc = slotDoc.get(V1Schema.F_ITEMSTACK, Document.class);
        if (itemDoc != null) {
            Object inner = itemDoc.get("amount");
            if (inner instanceof Number number) {
                return number.intValue();
            }
        }
        return 1;
    }

    private String containerType(Document doc) {
        String holder = doc.getString(V1Schema.F_HOLDER);
        if (holder != null && !holder.isBlank()) {
            return holder;
        }
        return doc.getString(V1Schema.F_TARGET);
    }

    private BlockLocation location(Document doc, boolean required) {
        BlockLocation direct = readLocationSubdoc(doc.get(V1Schema.F_LOCATION, Document.class));
        if (direct != null) {
            return direct;
        }
        if (required) {
            return new BlockLocation(null, null, 0, 0, 0);
        }
        return new BlockLocation(null, null, 0, 0, 0);
    }

    private BlockLocation requireLocation(Document doc) {
        BlockLocation direct = readLocationSubdoc(doc.get(V1Schema.F_LOCATION, Document.class));
        if (direct != null) {
            return direct;
        }
        throw new IllegalArgumentException("missing Location sub-document");
    }

    private BlockLocation readLocationSubdoc(Document locDoc) {
        if (locDoc == null) {
            return null;
        }
        int x = toInt(locDoc.get(V1Schema.F_LOC_X), 0);
        int y = toInt(locDoc.get(V1Schema.F_LOC_Y), 0);
        int z = toInt(locDoc.get(V1Schema.F_LOC_Z), 0);
        UUID worldId = parseUuid(locDoc.getString(V1Schema.F_LOC_WORLD));
        String worldName = worldNameLookup.nameFor(worldId);
        if (worldName == null && worldId != null) {
            worldName = worldId.toString();
        }
        return new BlockLocation(worldId, worldName, x, y, z);
    }

    private Source source(Document doc) {
        String player = doc.getString(V1Schema.F_PLAYER);
        if (player != null && !player.isBlank()) {
            UUID id = parseUuid(player);
            if (id != null) {
                return Source.player(id, null);
            }
        }
        String cause = doc.getString(V1Schema.F_CAUSE);
        if (cause == null || cause.isBlank()) {
            return Source.environment("unknown");
        }
        String trimmed = cause.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.equals("environment")) {
            return Source.environment("environment");
        }
        if (lower.equals("console")) {
            return Source.console();
        }
        if (lower.startsWith("pl@")) {
            return Source.plugin(trimmed.substring(3));
        }
        if (lower.startsWith("plugin:")) {
            return Source.plugin(trimmed.substring("plugin:".length()));
        }
        return Source.environment(trimmed);
    }

    private Origin origin(Document doc, String event, boolean placed) {
        String originField = doc.getString(V1Schema.F_ORIGIN);
        if (originField != null && !originField.isBlank()) {
            String lower = originField.toLowerCase(Locale.ROOT);
            return switch (lower) {
                case Origin.WORLDEDIT -> Origin.worldEdit();
                case Origin.FAWE -> Origin.fawe();
                case Origin.PLAYER -> Origin.player();
                default -> {
                    if (lower.startsWith("plugin:")) {
                        yield Origin.plugin(originField.substring("plugin:".length()));
                    }
                    yield Origin.environment(originField);
                }
            };
        }
        if (doc.getString(V1Schema.F_PLAYER) != null) {
            return Origin.player();
        }
        return Origin.environment(event);
    }

    private String readTarget(Document doc, BlockSnapshot fallback) {
        String target = doc.getString(V1Schema.F_TARGET);
        if (target != null && !target.isBlank()) {
            return target;
        }
        if (fallback != null && fallback.material() != null) {
            return fallback.material().name();
        }
        return "";
    }

    private Instant occurred(Document doc) {
        Object created = doc.get(V1Schema.F_CREATED);
        if (created instanceof Date date) {
            return date.toInstant();
        }
        return Instant.now();
    }

    private Instant expires(Document doc) {
        Object expires = doc.get(V1Schema.F_EXPIRES);
        if (expires instanceof Date date) {
            return date.toInstant();
        }
        return occurred(doc);
    }

    private List<String> readStringList(Object raw) {
        if (raw instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object value : list) {
                if (value != null) {
                    out.add(String.valueOf(value));
                }
            }
            return out;
        }
        return List.of();
    }

    private int parseSlot(String slot) {
        try {
            return Integer.parseInt(slot);
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    private int toInt(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ex) {
                return fallback;
            }
        }
        return fallback;
    }

    private double readDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text) {
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException ex) {
                return 0.0;
            }
        }
        return 0.0;
    }

    private Map<String, Object> toPlainMap(Document doc) {
        return doc;
    }

    private UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private List<UUID> parseRecipients(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<UUID> out = new ArrayList<>();
        for (String segment : raw.split(",")) {
            UUID id = parseUuid(segment.trim());
            if (id != null) {
                out.add(id);
            }
        }
        return out;
    }

    private String optionalString(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    public record Result(Kind kind, EventRecord record, String reason) {

        public enum Kind { TRANSLATED, SKIPPED_UNKNOWN, SKIPPED_DEFERRED, FAILED }

        public static Result translated(EventRecord record) {
            return new Result(Kind.TRANSLATED, record, null);
        }

        public static Result unknown(String reason) {
            return new Result(Kind.SKIPPED_UNKNOWN, null, reason);
        }

        public static Result skipped(String reason) {
            return new Result(Kind.SKIPPED_UNKNOWN, null, reason);
        }

        public static Result deferred(String event) {
            return new Result(Kind.SKIPPED_DEFERRED, null, event);
        }

        public static Result failed(String event, String reason) {
            return new Result(Kind.FAILED, null, event + ": " + reason);
        }
    }

    private record SlotPair(int slot, int amount, StoredItem before, StoredItem after) {
    }
}
