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
        String tags = null;
        // hasItemMeta() short-circuits the common no-NBT item (cobblestone,
        // plain tools): getItemMeta() allocates a fresh ItemMeta snapshot
        // even when there's nothing to extract, so skip it entirely there
        // (#98 micro-opt). Output is identical — a meta-less stack has no
        // name/lore/enchants.
        if (itemStack.hasItemMeta()) {
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
                // Custom-data projection (#140). getAsComponentString() renders
                // every set component; we lift just the minecraft:custom_data
                // compound back out so itags: can substring-match it. Only
                // reached for meta-bearing items, so the plain-item hot path
                // (no NBT, short-circuited above) never pays for it.
                tags = extractCustomData(meta.getAsComponentString());
            }
        }
        String data = includeData ? encode(itemStack) : null;
        return new StoredItem(slot, itemStack.getType().name(), data, name, lore, enchants, tags);
    }

    /**
     * Marker that opens the {@code minecraft:custom_data} component inside an
     * {@link ItemMeta#getAsComponentString()} rendering.
     */
    private static final String CUSTOM_DATA_MARKER = "minecraft:custom_data=";

    /**
     * Pull the {@code minecraft:custom_data} compound out of an item's
     * {@link ItemMeta#getAsComponentString()} rendering as a searchable
     * string, e.g. {@code {quest:"deliver_letter",PublicBukkitValues:{...}}}.
     *
     * <p>Custom data is where vanilla {@code /give ...[custom_data={...}]},
     * datapacks, and Bukkit {@code PersistentDataContainer}s all park their
     * payloads, so one substring-searchable projection ({@code itags:}) covers
     * every custom-item source without decoding NBT per row. Returns
     * {@code null} when the item has no custom data, or an empty {@code {}}
     * compound (nothing worth indexing).
     *
     * <p>The scan is brace-balanced and quote-aware so a string value
     * containing a stray {@code {}/}} can't truncate or over-run the captured
     * compound. Output is length-capped via {@link RecordingSupport#safeText}
     * to keep a pathological NBT blob from bloating a row. Package-private so
     * it can be unit-tested without a live server.
     */
    static String extractCustomData(String componentString) {
        if (componentString == null) {
            return null;
        }
        int marker = componentString.indexOf(CUSTOM_DATA_MARKER);
        if (marker < 0) {
            return null;
        }
        int start = marker + CUSTOM_DATA_MARKER.length();
        // Tolerate any whitespace the renderer might put between '=' and the
        // compound; the canonical form is compact but we don't want a stray
        // space to silently drop the capture.
        while (start < componentString.length()
                && Character.isWhitespace(componentString.charAt(start))) {
            start++;
        }
        // custom_data always serializes as a compound; anything else is junk.
        if (start >= componentString.length() || componentString.charAt(start) != '{') {
            return null;
        }
        int depth = 0;
        boolean inSingle = false;
        boolean inDouble = false;
        boolean escaped = false;
        for (int i = start; i < componentString.length(); i++) {
            char c = componentString.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (inSingle) {
                inSingle = c != '\'';
                continue;
            }
            if (inDouble) {
                inDouble = c != '"';
                continue;
            }
            switch (c) {
                case '\'' -> inSingle = true;
                case '"' -> inDouble = true;
                case '{' -> depth++;
                case '}' -> {
                    if (--depth == 0) {
                        String value = componentString.substring(start, i + 1);
                        return "{}".equals(value) ? null : RecordingSupport.safeText(value);
                    }
                }
                default -> {
                    // ordinary character inside the compound
                }
            }
        }
        // Unbalanced braces: malformed component string; index nothing rather
        // than capture a half-open compound.
        return null;
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
