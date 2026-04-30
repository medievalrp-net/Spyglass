package net.medievalrp.spyglass.importer.mapping;

import java.util.Locale;
import org.bukkit.Material;
import org.jetbrains.annotations.Nullable;

/**
 * Resolves CoreProtect's material strings to Bukkit {@link Material}
 * constants. CoreProtect stores them as either {@code "STONE"} or
 * {@code "minecraft:stone"} depending on age; both forms map to the
 * same enum constant.
 */
public final class Materials {

    private Materials() {}

    /** Returns the matching Material, or {@code null} if no match. */
    @Nullable
    public static Material resolve(@Nullable String coreProtectName) {
        if (coreProtectName == null || coreProtectName.isEmpty()) {
            return null;
        }
        String stripped = coreProtectName;
        int colon = stripped.indexOf(':');
        if (colon >= 0) {
            stripped = stripped.substring(colon + 1);
        }
        try {
            return Material.valueOf(stripped.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            // Material was renamed or removed in a later MC version. The
            // mapper degrades to AIR rather than crashing the import.
            return null;
        }
    }

    /**
     * Coalesce the source-resolved block-state string with a fallback
     * built from the Material enum value. Used when the source
     * produced {@code null} (no state attributes captured) or the row
     * predates CoreProtect's blockdata-map era.
     */
    public static String coalesceBlockData(@Nullable String sourceResolved,
                                           Material fallback) {
        if (sourceResolved != null && !sourceResolved.isEmpty()) {
            return sourceResolved;
        }
        if (fallback != null) {
            return "minecraft:" + fallback.name().toLowerCase(Locale.ROOT);
        }
        return "minecraft:air";
    }
}
