package net.medievalrp.spyglass.importer.source;

import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/** Denormalized {@code co_chat} or {@code co_command} row. */
public record CoreProtectChatRow(
        long rowid,
        long timeEpochSeconds,
        String worldName,
        int x, int y, int z,
        String message,
        String playerName,
        @Nullable UUID playerUuid) {
}
