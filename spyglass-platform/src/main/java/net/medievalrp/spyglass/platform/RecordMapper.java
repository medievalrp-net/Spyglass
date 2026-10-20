package net.medievalrp.spyglass.platform;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.crusalis.platform.sdk.api.EventSlot;
import net.crusalis.platform.sdk.api.JsonWriter;
import net.medievalrp.spyglass.api.event.BlockBreakRecord;
import net.medievalrp.spyglass.api.event.BlockPlaceRecord;
import net.medievalrp.spyglass.api.event.BlockSnapshot;
import net.medievalrp.spyglass.api.event.BlockUseRecord;
import net.medievalrp.spyglass.api.event.ChatRecord;
import net.medievalrp.spyglass.api.event.CommandRecord;
import net.medievalrp.spyglass.api.event.ContainerDepositRecord;
import net.medievalrp.spyglass.api.event.ContainerInteractRecord;
import net.medievalrp.spyglass.api.event.ContainerWithdrawRecord;
import net.medievalrp.spyglass.api.event.CustomRecord;
import net.medievalrp.spyglass.api.event.EntityDeathRecord;
import net.medievalrp.spyglass.api.event.EntityHitRecord;
import net.medievalrp.spyglass.api.event.EntityLifecycleRecord;
import net.medievalrp.spyglass.api.event.EntityMountRecord;
import net.medievalrp.spyglass.api.event.EntityNameRecord;
import net.medievalrp.spyglass.api.event.EventRecord;
import net.medievalrp.spyglass.api.event.ItemDropRecord;
import net.medievalrp.spyglass.api.event.ItemPickupRecord;
import net.medievalrp.spyglass.api.event.JoinRecord;
import net.medievalrp.spyglass.api.event.QuitRecord;
import net.medievalrp.spyglass.api.event.RollbackOpRecord;
import net.medievalrp.spyglass.api.event.StoredItem;
import net.medievalrp.spyglass.api.event.TeleportRecord;
import net.medievalrp.spyglass.api.util.BlockLocation;
import org.bukkit.Material;

/**
 * Writes one Spyglass record into one Crusalis event slot.
 *
 * <p>Every payload has the same seven fields plus {@code data} (§6.2), written in that
 * order, which is also the order the typed table's columns are in — the base fields are
 * promoted to columns by the materialized view, and {@code data} is the only part that
 * varies by action.
 *
 * <p>What is deliberately not written:
 *
 * <ul>
 *   <li><b>The acting player's name.</b> The envelope carries the uuid, and identity
 *       resolution is the sink's job. A name in the payload is a name that is wrong the
 *       day the player changes it.</li>
 *   <li><b>The IP on a join record.</b> D12. See {@link Ips}.</li>
 *   <li><b>Item NBT and entity NBT blobs.</b> They are kilobytes per event — the slot's
 *       payload budget is 2 KiB — and they are still in Spyglass's native storage,
 *       which the inspector wand reads. A material, a name and an enchantment summary
 *       is what a ClickHouse query can actually use.</li>
 * </ul>
 *
 * <p>This class never allocates per event beyond what the slot's writer does: no
 * builders, no maps, no string concatenation on the hot path. It is called from the
 * server's main thread once per logged action, which on a war Saturday is thousands of
 * times a second.
 */
final class RecordMapper {

    /**
     * {@code "minecraft:stone"} per {@link Material}, built once.
     *
     * <p>Not a concatenation per event. The benchmark caught this: building the key
     * from the enum name cost 160 B/op on the block-break path — on the main thread,
     * twice per broken block, against a budget of zero. An array indexed by ordinal is
     * the cheapest possible lookup and the strings are immutable, so there is nothing
     * to invalidate.
     */
    private static final String[] MATERIAL_KEYS;

    static {
        Material[] materials = Material.values();
        MATERIAL_KEYS = new String[materials.length];
        for (Material material : materials) {
            MATERIAL_KEYS[material.ordinal()] = "minecraft:"
                    + material.name().toLowerCase(Locale.ROOT);
        }
    }

    private RecordMapper() {
    }

    /** Fills {@code slot} from {@code record}. */
    static void write(EventRecord record, EventSlot slot, PlatformSettings settings) {
        UUID actor = record.source() == null ? null : record.source().playerId();
        if (actor != null) {
            slot.uuid(actor.getMostSignificantBits(), actor.getLeastSignificantBits());
        }

        BlockLocation location = record.location();
        JsonWriter payload = slot.payload();
        payload.str("world", location == null ? "" : nullToEmpty(location.worldName()));
        payload.i32("x", location == null ? 0 : location.x());
        payload.i32("y", location == null ? 0 : location.y());
        payload.i32("z", location == null ? 0 : location.z());
        payload.str("cause", Causes.of(record));

        // target, amount, text and data are per action. Every branch writes all four,
        // in that order, because the envelope's field order is part of the contract
        // (§4.5) and a branch that skips one would produce a different shape for the
        // same type.
        switch (record) {
            case BlockBreakRecord r -> {
                payload.str("target", nullToEmpty(r.target()));
                payload.i32("amount", 1);
                payload.str("text", "");
                payload.object("data");
                block(payload, "was", "was_data", r.originalBlock());
                block(payload, "now", "now_data", r.newBlock());
                payload.endObject();
            }
            case BlockPlaceRecord r -> {
                payload.str("target", nullToEmpty(r.target()));
                payload.i32("amount", 1);
                payload.str("text", "");
                payload.object("data");
                block(payload, "was", "was_data", r.originalBlock());
                block(payload, "now", "now_data", r.newBlock());
                payload.endObject();
            }
            case ChatRecord r -> {
                payload.str("target", "");
                payload.i32("amount", 1);
                payload.str("text", nullToEmpty(r.message()));
                payload.object("data");
                // The count, not the list: a shout heard by 300 players would be 300
                // uuids in a 2 KiB payload, and the recipients are reconstructable
                // from session events anyway.
                payload.i32("recipients", r.recipients() == null ? 0 : r.recipients().size());
                extensions(payload, r.extensions());
                payload.endObject();
            }
            case CommandRecord r -> {
                // Resolved once and used three times. The benchmark showed the three
                // calls to name() on the original line adding up to half of this
                // action's allocation.
                String name = CommandLines.name(r.commandLine());
                String line = CommandLines.redact(r.commandLine(), name,
                        settings.redactCommands());
                payload.str("target", name);
                payload.i32("amount", 1);
                payload.str("text", line);
                payload.object("data");
                if (CommandLines.isPrivateName(name, settings.privateCommands())) {
                    // Makes the row moderator-only through the ClickHouse row policy.
                    payload.bool("private", true);
                }
                payload.endObject();
            }
            case ContainerDepositRecord r -> {
                payload.str("target", nullToEmpty(r.target()));
                payload.i32("amount", r.amount());
                payload.str("text", "");
                payload.object("data");
                payload.str("container", nullToEmpty(r.containerType()));
                payload.i32("slot", r.slot());
                item(payload, "item", r.afterItem() != null ? r.afterItem() : r.beforeItem());
                payload.endObject();
            }
            case ContainerWithdrawRecord r -> {
                payload.str("target", nullToEmpty(r.target()));
                payload.i32("amount", r.amount());
                payload.str("text", "");
                payload.object("data");
                payload.str("container", nullToEmpty(r.containerType()));
                payload.i32("slot", r.slot());
                item(payload, "item", r.beforeItem() != null ? r.beforeItem() : r.afterItem());
                payload.endObject();
            }
            case ContainerInteractRecord r -> {
                payload.str("target", nullToEmpty(r.target()));
                payload.i32("amount", 1);
                payload.str("text", "");
                payload.object("data");
                // open / close / shulker-open / shulker-close all map to one type, so
                // the key is the only thing that says which happened.
                payload.str("action", nullToEmpty(r.event()));
                payload.endObject();
            }
            case BlockUseRecord r -> {
                payload.str("target", nullToEmpty(r.target()));
                payload.i32("amount", 1);
                payload.str("text", "");
                payload.object("data");
                payload.str("action", nullToEmpty(r.event()));
                payload.endObject();
            }
            case ItemDropRecord r -> {
                payload.str("target", nullToEmpty(r.target()));
                payload.i32("amount", r.amount());
                payload.str("text", "");
                payload.object("data");
                item(payload, "item", r.item());
                payload.endObject();
            }
            case ItemPickupRecord r -> {
                payload.str("target", nullToEmpty(r.target()));
                payload.i32("amount", r.amount());
                payload.str("text", "");
                payload.object("data");
                item(payload, "item", r.item());
                if ("snapshot-take".equalsIgnoreCase(r.event())) {
                    // A staff action that looks exactly like a pickup, and the one
                    // thing a reviewer of this type needs to be able to filter on.
                    payload.bool("staff", true);
                }
                extensions(payload, r.extensions());
                payload.endObject();
            }
            case EntityDeathRecord r -> {
                payload.str("target", nullToEmpty(r.entityType()));
                payload.i32("amount", 1);
                payload.str("text", "");
                payload.object("data");
                payload.str("killer", nullToEmpty(r.killerType()));
                payload.str("damage_cause", nullToEmpty(r.damageCause()));
                uuidField(payload, "entity_id", r.entityId());
                // entityNbt is deliberately dropped: kilobytes of item and attribute
                // data per death, already in native storage for the wand.
                payload.endObject();
            }
            case EntityHitRecord r -> {
                payload.str("target", nullToEmpty(r.victimType()));
                payload.i32("amount", 1);
                payload.str("text", "");
                payload.object("data");
                payload.f64("damage", r.damage());
                payload.bool("projectile", r.projectile());
                payload.str("projectile_type", nullToEmpty(r.projectileType()));
                uuidField(payload, "victim_id", r.victimId());
                payload.endObject();
            }
            case EntityMountRecord r -> {
                payload.str("target", nullToEmpty(r.mountType()));
                payload.i32("amount", 1);
                payload.str("text", "");
                payload.object("data");
                payload.str("action", r.dismount() ? "dismount" : "mount");
                uuidField(payload, "mount_id", r.mountId());
                payload.endObject();
            }
            case EntityNameRecord r -> {
                payload.str("target", nullToEmpty(r.entityType()));
                payload.i32("amount", 1);
                payload.str("text", nullToEmpty(r.newName()));
                payload.object("data");
                payload.str("old_name", nullToEmpty(r.oldName()));
                uuidField(payload, "entity_id", r.entityId());
                payload.endObject();
            }
            case TeleportRecord r -> {
                payload.str("target", nullToEmpty(r.target()));
                payload.i32("amount", 1);
                payload.str("text", "");
                payload.object("data");
                locationField(payload, "from", r.from());
                locationField(payload, "to", r.to());
                payload.str("teleport_cause", nullToEmpty(r.cause()));
                payload.endObject();
            }
            case JoinRecord r -> {
                payload.str("target", nullToEmpty(r.target()));
                payload.i32("amount", 1);
                payload.str("text", "");
                // r.address() is NEVER read. D12: no spyglass.* event carries an IP,
                // and the one record that has one is this one.
                payload.object("data");
                payload.endObject();
            }
            case QuitRecord r -> {
                payload.str("target", nullToEmpty(r.target()));
                payload.i32("amount", 1);
                payload.str("text", "");
                payload.object("data");
                payload.endObject();
            }
            case RollbackOpRecord r -> {
                payload.str("target", "");
                payload.i32("amount", 1);
                payload.str("text", nullToEmpty(r.reference()));
                payload.object("data");
                payload.str("kind", nullToEmpty(r.mode()));
                payload.str("job_id", nullToEmpty(r.reference()));
                payload.endObject();
            }
            case EntityLifecycleRecord r -> {
                payload.str("target", nullToEmpty(r.entityType()));
                payload.i32("amount", 1);
                payload.str("text", "");
                payload.object("data");
                payload.str("action", nullToEmpty(r.event()));
                uuidField(payload, "entity_id", r.entityId());
                // entityNbt dropped, as on a death: kilobytes of item data per event,
                // and the rollback reads it from native storage, not from here.
                payload.endObject();
            }
            case CustomRecord r -> {
                payload.str("target", nullToEmpty(r.target()));
                payload.i32("amount", 1);
                payload.str("text", nullToEmpty(r.message()));
                payload.object("data");
                payload.str("action", nullToEmpty(r.event()));
                extensions(payload, r.extensions());
                payload.endObject();
            }
        }
    }

    private static void block(JsonWriter payload, String key, String dataKey,
                              BlockSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        // The material and its block data, and nothing else: container contents, sign
        // text and banner patterns are what make a snapshot kilobytes long, and they
        // are in native storage, which is what the rollback and the wand read.
        // "minecraft:stone", the same spelling Spyglass's importer writes (Materials in
        // spyglass-core) and the same one target carries, so a Crusalis query can match
        // one field against the other. From the table, not built here: see MATERIAL_KEYS.
        Material material = snapshot.material();
        payload.str(key, material == null ? "" : MATERIAL_KEYS[material.ordinal()]);
        if (snapshot.blockData() != null && !snapshot.blockData().isEmpty()) {
            // The key is passed in rather than concatenated: key + "_data" is a string
            // per event, which is the allocation this whole class is written to avoid.
            payload.str(dataKey, snapshot.blockData());
        }
    }

    private static void item(JsonWriter payload, String key, StoredItem stored) {
        if (stored == null) {
            return;
        }
        payload.object(key);
        payload.str("material", nullToEmpty(stored.material()));
        if (stored.name() != null && !stored.name().isBlank()) {
            payload.str("name", stored.name());
        }
        if (stored.enchants() != null && !stored.enchants().isEmpty()) {
            payload.i32("enchants", stored.enchants().size());
        }
        payload.endObject();
    }

    private static void extensions(JsonWriter payload, Map<String, String> extensions) {
        if (extensions == null || extensions.isEmpty()) {
            return;
        }
        payload.object("ext");
        for (Map.Entry<String, String> entry : extensions.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            // An integrator's own key/value. Checked rather than trusted: an extension
            // that put an address in a channel name would otherwise publish it.
            if (Ips.looksLikeIp(entry.getValue())) {
                continue;
            }
            payload.str(entry.getKey(), entry.getValue());
        }
        payload.endObject();
    }

    private static void locationField(JsonWriter payload, String key, BlockLocation location) {
        if (location == null) {
            return;
        }
        payload.object(key);
        payload.str("world", nullToEmpty(location.worldName()));
        payload.i32("x", location.x());
        payload.i32("y", location.y());
        payload.i32("z", location.z());
        payload.endObject();
    }

    private static void uuidField(JsonWriter payload, String key, UUID value) {
        if (value != null) {
            payload.str(key, value.toString());
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
