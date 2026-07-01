package net.medievalrp.spyglass.plugin.importer.source;

import java.util.Map;
import org.jetbrains.annotations.Nullable;

/**
 * Reconstructs a Bukkit-shaped block-state string (e.g.
 * {@code minecraft:oak_log[axis=y,waterlogged=false]}) from
 * CoreProtect's compressed on-disk format.
 *
 * <h2>On-disk format</h2>
 *
 * CoreProtect 18+ writes {@code co_block.blockdata} as a UTF-8 ASCII
 * string of comma-separated integer ids. Each id is a foreign key into
 * {@code co_blockdata_map.id}, whose {@code data} column holds a single
 * key=value token like {@code axis=y}. The block name and the wrapping
 * brackets are stripped on disk and re-added at read time.
 *
 * <p>So a block placed as {@code minecraft:oak_log[axis=y,waterlogged=false]}
 * lands in {@code co_block.blockdata} as something like {@code "12,7"},
 * where the numbers depend on the order CoreProtect interned each token.
 *
 * <p>Empty / null {@code blockdata} means "no state attributes" — the
 * resolved string is just the namespaced material name with no brackets.
 */
public final class BlockDataResolver {

    private BlockDataResolver() {}

    /**
     * Resolve a raw {@code co_block.blockdata} string against the
     * pre-loaded {@code co_blockdata_map}. Returns {@code null} when
     * the row has no state attributes; the caller substitutes a bare
     * material name in that case.
     *
     * @param materialName  CoreProtect's stored material name (e.g.
     *                      {@code "minecraft:oak_log"}). Already
     *                      namespaced and lowercased on disk.
     * @param rawBlockData  raw value of {@code co_block.blockdata}, as
     *                      a UTF-8 string of comma-separated integer
     *                      ids; may be null/empty.
     * @param blockDataMap  pre-loaded
     *                      {@code co_blockdata_map (id → data)}.
     */
    @Nullable
    public static String resolve(@Nullable String materialName,
                                 @Nullable String rawBlockData,
                                 Map<Integer, String> blockDataMap) {
        if (materialName == null || materialName.isEmpty()) return null;
        if (rawBlockData == null || rawBlockData.isEmpty()) {
            return materialName;
        }
        String trimmed = rawBlockData.trim();
        if (trimmed.isEmpty() || "0".equals(trimmed)) {
            return materialName;
        }
        // Parse the CSV of token ids. CoreProtect uses an int-only
        // alphabet here, so anything non-numeric is corrupt and we
        // fall back to the bare material name rather than emit a
        // half-decoded string.
        String[] parts = trimmed.split(",");
        StringBuilder tokens = new StringBuilder(parts.length * 16);
        boolean anyResolved = false;
        for (String part : parts) {
            String idStr = part.trim();
            if (idStr.isEmpty()) continue;
            int id;
            try {
                id = Integer.parseInt(idStr);
            } catch (NumberFormatException ex) {
                return materialName; // give up on this row
            }
            String token = blockDataMap.get(id);
            if (token == null || token.isEmpty()) continue;
            if (anyResolved) tokens.append(',');
            tokens.append(token);
            anyResolved = true;
        }
        if (!anyResolved) return materialName;
        return materialName + "[" + tokens + "]";
    }
}
