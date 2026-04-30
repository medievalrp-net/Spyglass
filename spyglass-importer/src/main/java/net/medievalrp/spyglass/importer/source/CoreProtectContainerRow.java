package net.medievalrp.spyglass.importer.source;

import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Denormalized {@code co_container} row.
 *
 * <p>{@code action == 0} = item removed, {@code action == 1} = item added.
 * CoreProtect doesn't store the slot index in a column — slot identity is
 * baked into the metadata blob (and only meaningful for armor stands).
 * For ordinary containers the slot is effectively lost; we emit
 * {@code slot = -1} on the Spyglass side.
 *
 * <p>The {@code metadata} blob is a {@link java.io.Serializable Serializable}
 * stream produced by {@code BukkitObjectOutputStream}; decode with
 * {@code ItemMetaDecoder}.
 */
public record CoreProtectContainerRow(
        long rowid,
        long timeEpochSeconds,
        String worldName,
        int x, int y, int z,
        String materialName,
        int amount,
        int action,
        boolean rolledBack,
        @Nullable byte[] metadata,
        String playerName,
        @Nullable UUID playerUuid) {
}
