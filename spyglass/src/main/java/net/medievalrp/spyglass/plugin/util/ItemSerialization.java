package net.medievalrp.spyglass.plugin.util;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.medievalrp.spyglass.api.event.StoredItem;
import net.medievalrp.spyglass.plugin.listener.RecordingSupport;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public final class ItemSerialization {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private ItemSerialization() {
    }

    public static String encode(ItemStack itemStack) {
        if (itemStack == null) {
            return null;
        }
        return Base64.getEncoder().encodeToString(itemStack.serializeAsBytes());
    }

    public static ItemStack decode(String data) {
        if (data == null || data.isBlank()) {
            return null;
        }
        return ItemStack.deserializeBytes(Base64.getDecoder().decode(data));
    }

    /**
     * Serialize a stack for storage. Extracts display name, lore, and
     * enchantments into indexable plain-text fields alongside the opaque
     * base64 blob so operators can query by
     * {@code iname:} / {@code ilore:} / {@code ench:}. The blob is the
     * source of truth for faithful reconstruction (rollback, salvage), so
     * this variant is for records that may be replayed.
     */
    public static StoredItem storedItem(int slot, ItemStack itemStack) {
        return build(slot, itemStack, true);
    }

    /**
     * Projection-only variant: identical to {@link #storedItem} but leaves
     * {@code data} {@code null}, skipping the {@code serializeAsBytes()} +
     * Base64 encode that dominates per-item allocation. Use for forensic
     * records that are never rolled back or salvaged
     * ({@code ItemPickupRecord}, {@code ItemDropRecord}) — nothing ever
     * decodes their blob, so it is pure dead weight in memory and on disk
     * (#103). {@code material}, {@code name}, {@code lore} and
     * {@code enchants} are populated exactly as in {@link #storedItem}, so
     * {@code imaterial:} / {@code iname:} / {@code ilore:} / {@code ench:} /
     * {@code cu:} queries match unchanged.
     */
    public static StoredItem storedItemProjection(int slot, ItemStack itemStack) {
        return build(slot, itemStack, false);
    }

    private static StoredItem build(int slot, ItemStack itemStack, boolean includeData) {
        if (itemStack == null || itemStack.getType() == Material.AIR) {
            return null;
        }
        String name = null;
        List<String> lore = List.of();
        List<String> enchants = List.of();
        ItemMeta meta = itemStack.getItemMeta();
        if (meta != null) {
            if (meta.hasDisplayName()) {
                Component displayName = meta.displayName();
                if (displayName != null) {
                    name = RecordingSupport.safeText(PLAIN.serialize(displayName));
                }
            }
            if (meta.hasLore()) {
                List<Component> metaLore = meta.lore();
                if (metaLore != null && !metaLore.isEmpty()) {
                    List<String> out = new ArrayList<>(metaLore.size());
                    for (Component line : metaLore) {
                        out.add(RecordingSupport.safeText(PLAIN.serialize(line)));
                    }
                    lore = out;
                }
            }
            if (meta.hasEnchants()) {
                enchants = enchantStrings(meta.getEnchants());
            }
        }
        String data = includeData ? encode(itemStack) : null;
        return new StoredItem(slot, itemStack.getType().name(), data, name, lore, enchants);
    }

    /**
     * Render Bukkit's {@code Map<Enchantment, Integer>} into stable strings
     * like {@code "sharpness=5"}. Lowercased so {@code ench:sharp} matches
     * regardless of the user's casing.
     */
    private static List<String> enchantStrings(Map<Enchantment, Integer> enchants) {
        if (enchants == null || enchants.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(enchants.size());
        for (Map.Entry<Enchantment, Integer> entry : enchants.entrySet()) {
            String key = entry.getKey().getKey().getKey().toLowerCase(Locale.ROOT);
            out.add(key + "=" + entry.getValue());
        }
        return out;
    }
}
