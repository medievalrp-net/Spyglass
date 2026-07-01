package net.medievalrp.spyglass.plugin.importer.source;

import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Denormalized {@code co_session} row.
 *
 * <p>{@code action == 0} = logout, {@code action == 1} = login. CoreProtect
 * does <strong>not</strong> store IP addresses — there is no equivalent of
 * Spyglass's {@code JoinRecord.address} on the source side. Imported join
 * rows will have a null address.
 */
public record CoreProtectSessionRow(
        long rowid,
        long timeEpochSeconds,
        String worldName,
        int x, int y, int z,
        int action,
        String playerName,
        @Nullable UUID playerUuid) {
}
