package net.medievalrp.spyglass.importer.mapping;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.configuration.serialization.ConfigurationSerialization;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.jetbrains.annotations.Nullable;

/**
 * Decodes CoreProtect's item metadata blobs into Bukkit {@link ItemStack}
 * instances.
 *
 * <p>The blob is a {@code BukkitObjectOutputStream} stream (Java
 * serialization, magic {@code 0xACED 0x0005}) of a
 * {@code List<List<Map<String, Object>>>} where the outer list groups
 * "metadata sections" written by CoreProtect's {@code ItemMetaHandler}.
 * Section 0 is always the headline {@code ItemMeta} map; later sections
 * are sub-meta (potion effects, banner patterns, firework charges, etc.)
 * that we don't need to reconstruct individually because Bukkit's
 * {@link ConfigurationSerialization} round-trips them.
 *
 * <p>Decoding requires Bukkit/Paper on the classpath at runtime — we
 * shade {@code paper-api} into the importer fat jar specifically for
 * this. A failure to decode (corrupt blob, unknown ConfigurationSerializable
 * subclass, MC version mismatch on serialized fields) is always
 * non-fatal: the decoder returns {@code null} and the mapper falls back
 * to a base {@link StoredItem} carrying just material + amount.
 */
public final class ItemMetaDecoder {

    private static final Logger LOGGER = Logger.getLogger(ItemMetaDecoder.class.getName());

    private ItemMetaDecoder() {}

    /**
     * Decode the headline {@code ItemMeta} map from a CoreProtect blob.
     * Returns {@code null} if the blob is null/empty/corrupt.
     */
    @Nullable
    public static ItemMeta decodeMeta(@Nullable byte[] blob) {
        Map<String, Object> headline = decodeHeadlineMap(blob);
        if (headline == null) return null;
        try {
            Object deserialized = ConfigurationSerialization.deserializeObject(headline);
            return (deserialized instanceof ItemMeta meta) ? meta : null;
        } catch (RuntimeException ex) {
            LOGGER.log(Level.FINE, "ConfigurationSerialization rejected ItemMeta map", ex);
            return null;
        }
    }

    /**
     * Decode just the top-level meta {@code Map} without trying to
     * resolve it through {@link ConfigurationSerialization}. Useful for
     * field projections (custom name, lore, enchants) that don't need a
     * full ItemMeta — they just read the map by key.
     */
    @SuppressWarnings("unchecked")
    @Nullable
    public static Map<String, Object> decodeHeadlineMap(@Nullable byte[] blob) {
        if (blob == null || blob.length < 4) return null;
        Object root;
        try (BukkitObjectInputStream in = new BukkitObjectInputStream(
                new ByteArrayInputStream(blob))) {
            root = in.readObject();
        } catch (IOException | ClassNotFoundException ex) {
            LOGGER.log(Level.FINE, "Item meta blob did not deserialize", ex);
            return null;
        } catch (RuntimeException ex) {
            LOGGER.log(Level.FINE, "Item meta blob threw during deserialize", ex);
            return null;
        }
        if (!(root instanceof List<?> outer) || outer.isEmpty()) return null;
        Object firstSection = outer.get(0);
        if (!(firstSection instanceof List<?> sectionRows) || sectionRows.isEmpty()) {
            return null;
        }
        Object firstRow = sectionRows.get(0);
        if (!(firstRow instanceof Map<?, ?> map)) return null;
        // Bukkit serialization always uses String keys; the cast is safe
        // for well-formed blobs and the runtime cost is one isinstance.
        Map<String, Object> result = new java.util.HashMap<>(map.size());
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (e.getKey() instanceof String k) {
                result.put(k, e.getValue());
            }
        }
        return result;
    }

    /**
     * Extracts plain-text projections (custom name, lore lines, enchant
     * names) from a decoded meta map. Returns null fields when the map
     * doesn't carry that key — operators searching by {@code n:} /
     * {@code lore:} / {@code e:} get the same behaviour as native
     * Spyglass rows.
     */
    public record Projections(@Nullable String displayName,
                              List<String> lore,
                              List<String> enchants) {
        public Projections {
            lore = lore == null ? List.of() : List.copyOf(lore);
            enchants = enchants == null ? List.of() : List.copyOf(enchants);
        }

        public static Projections empty() {
            return new Projections(null, List.of(), List.of());
        }
    }

    @SuppressWarnings("unchecked")
    public static Projections projectionsFrom(@Nullable Map<String, Object> headline) {
        if (headline == null) return Projections.empty();
        String displayName = stringOrNull(headline.get("display-name"));
        List<String> lore = headline.get("lore") instanceof List<?> raw
                ? toStringList(raw) : List.of();
        // enchants is a Map<String, Integer> in Bukkit serialization;
        // surface it as "<NAME>:<level>" strings so an operator's
        // e:SHARPNESS predicate matches.
        List<String> enchants = List.of();
        Object enchObj = headline.get("enchants");
        if (enchObj instanceof Map<?, ?> raw) {
            List<String> out = new ArrayList<>(raw.size());
            for (Map.Entry<?, ?> e : raw.entrySet()) {
                if (e.getKey() instanceof String name) {
                    out.add(name + ":" + String.valueOf(e.getValue()));
                }
            }
            enchants = out;
        }
        return new Projections(displayName, lore, enchants);
    }

    @Nullable
    private static String stringOrNull(@Nullable Object o) {
        return o == null ? null : o.toString();
    }

    private static List<String> toStringList(List<?> raw) {
        List<String> out = new ArrayList<>(raw.size());
        for (Object o : raw) {
            if (o != null) out.add(o.toString());
        }
        return out;
    }
}
