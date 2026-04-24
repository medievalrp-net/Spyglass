package net.medievalrp.spyglass.plugin.util;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.medievalrp.spyglass.api.event.StoredItem;
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
     * {@code iname:} / {@code ilore:} / {@code ench:}.
     */
    public static StoredItem storedItem(int slot, ItemStack itemStack) {
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
                    name = PLAIN.serialize(displayName);
                }
            }
            if (meta.hasLore()) {
                List<Component> metaLore = meta.lore();
                if (metaLore != null && !metaLore.isEmpty()) {
                    List<String> out = new ArrayList<>(metaLore.size());
                    for (Component line : metaLore) {
                        out.add(PLAIN.serialize(line));
                    }
                    lore = out;
                }
            }
            if (meta.hasEnchants()) {
                enchants = enchantStrings(meta.getEnchants());
            }
        }
        return new StoredItem(slot, itemStack.getType().name(),
                encode(itemStack), name, lore, enchants);
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
