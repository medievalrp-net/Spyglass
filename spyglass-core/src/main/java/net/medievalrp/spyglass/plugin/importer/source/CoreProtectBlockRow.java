package net.medievalrp.spyglass.plugin.importer.source;

import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Denormalized {@code co_block} row, with lookup-table joins resolved
 * to strings/UUIDs so the mapper has everything it needs without going
 * back to the DB.
 *
 * <h2>Action overload</h2>
 *
 * CoreProtect overloads {@code co_block} to also store kill events:
 * <ul>
 *   <li>{@code action == 0} — block broken</li>
 *   <li>{@code action == 1} — block placed</li>
 *   <li>{@code action == 2} — interact (right-click)</li>
 *   <li>{@code action == 3} — kill: when {@code type == 0} a player
 *       was killed (their name is in {@link #killedPlayerName});
 *       otherwise an entity was killed (its namespaced type is in
 *       {@link #killedEntityType}). The {@code playerUuid} is the
 *       killer.</li>
 * </ul>
 *
 * <p>{@link #playerUuid} may be {@code null} for ancient pre-UUID
 * CoreProtect rows; the mapper warns and skips those.
 */
public record CoreProtectBlockRow(
        long rowid,
        long timeEpochSeconds,
        String worldName,
        int x, int y, int z,
        @Nullable String materialName,
        @Nullable String blockData,
        int action,
        boolean rolledBack,
        String playerName,
        @Nullable UUID playerUuid,
        @Nullable String killedEntityType,
        @Nullable String killedPlayerName,
        @Nullable UUID killedPlayerUuid) {
}
