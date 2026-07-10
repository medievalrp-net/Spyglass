package net.medievalrp.spyglass.plugin.command.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.medievalrp.spyglass.api.query.Flag;
import net.medievalrp.spyglass.api.query.QueryPredicate;
import net.medievalrp.spyglass.api.query.QueryRequest;
import net.medievalrp.spyglass.api.query.Sort;
import net.medievalrp.spyglass.api.util.Duration;
import net.medievalrp.spyglass.plugin.config.SpyglassConfig;
import net.medievalrp.spyglass.plugin.rollback.RollbackEngine;
import net.medievalrp.spyglass.plugin.rollback.SqliteUndoStack;
import net.medievalrp.spyglass.plugin.rollback.UndoStack;
import net.medievalrp.spyglass.plugin.storage.SqliteRecordStore;
import net.medievalrp.spyglass.plugin.storage.UndoReferenceBson;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

/**
 * Regression tests for #266: a reference that can never replay (corrupt
 * blob, unknown mode) must be tombstoned, not merely closed. Before the
 * fix the poison row stayed newest in the ledger and every subsequent
 * /undo re-popped it, soft-locking the player's whole undo history for
 * the 24h TTL. Runs against the real SQLite stack.
 */
class UndoServiceCorruptReferenceTest {

    @TempDir
    Path dir;
    private SqliteRecordStore store;

    @BeforeEach
    void open() {
        store = new SqliteRecordStore(dir.resolve("spyglass.db"));
    }

    @AfterEach
    void close() {
        store.close();
    }

    private static SpyglassConfig fakeConfig() {
        SpyglassConfig.Limits limits = new SpyglassConfig.Limits(
                250, 1_000, 50, 4_000, Duration.parse("30s"), 40L);
        return mock(SpyglassConfig.class, invocation -> {
            if ("limits".equals(invocation.getMethod().getName())) {
                return limits;
            }
            return null;
        });
    }

    private static String validBlob(UUID playerId) {
        QueryRequest valid = new QueryRequest(
                List.of(new QueryPredicate.Eq("source.playerId", playerId)),
                Sort.NEWEST_FIRST, 50, EnumSet.of(Flag.NO_GROUP), false);
        return UndoReferenceBson.encodeBase64(valid, "ROLLBACK",
                Instant.parse("2026-06-10T02:00:00Z"));
    }

    private void assertPoisonRowIsConsumedAndOlderRefIsReachable(
            SqliteUndoStack stack, UUID playerId, Player player,
            List<Component> messages, String expectedFragment) {
        RollbackService rollbacks = mock(RollbackService.class);
        UndoService undo = new UndoService(mock(RollbackEngine.class), stack,
                ServiceSupport.synchronous(), fakeConfig(), rollbacks, null);

        // First /undo hits the unusable newest reference: it is reported
        // AND removed from the ledger.
        undo.execute(player);
        assertThat(ServiceTestSupport.plainTexts(messages))
                .anyMatch(line -> line.contains(expectedFragment)
                        && line.contains("run /sg undo again"));
        verify(rollbacks, never()).executeReplay(any(), any(), any(), any(), any());
        UndoStack.Popped next = stack.openLatest(playerId).orElseThrow();
        assertThat(((UndoStack.ReplayReference) next).referenceBase64())
                .as("the poison row must be tombstoned so the older valid reference surfaces")
                .isEqualTo(validBlob(playerId));
        next.close();

        // Second /undo reaches the valid reference and queues the replay.
        undo.execute(player);
        ArgumentCaptor<RollbackMode> mode = ArgumentCaptor.forClass(RollbackMode.class);
        verify(rollbacks).executeReplay(eq(player), any(QueryRequest.class),
                mode.capture(), any(String.class), any(Runnable.class));
        assertThat(mode.getValue()).isEqualTo(RollbackMode.RESTORE);
    }

    @Test
    void corruptReferenceIsTombstonedAndDoesNotBlockOlderEntries() throws Exception {
        SqliteUndoStack stack = new SqliteUndoStack(store);
        UUID playerId = UUID.randomUUID();
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerId);
        List<Component> messages = ServiceTestSupport.captureMessages(player);

        stack.pushReference(playerId, "ROLLBACK", validBlob(playerId));
        Thread.sleep(20); // distinct created_at so the poison row is strictly newer
        stack.pushReference(playerId, "ROLLBACK", "%%%not-base64-bson%%%");

        assertPoisonRowIsConsumedAndOlderRefIsReachable(
                stack, playerId, player, messages, "Undo reference unreadable");
    }

    @Test
    void unknownModeReferenceIsTombstonedAndDoesNotBlockOlderEntries() throws Exception {
        SqliteUndoStack stack = new SqliteUndoStack(store);
        UUID playerId = UUID.randomUUID();
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerId);
        List<Component> messages = ServiceTestSupport.captureMessages(player);

        stack.pushReference(playerId, "ROLLBACK", validBlob(playerId));
        Thread.sleep(20);
        QueryRequest request = new QueryRequest(
                List.of(new QueryPredicate.Eq("source.playerId", playerId)),
                Sort.NEWEST_FIRST, 50, EnumSet.of(Flag.NO_GROUP), false);
        stack.pushReference(playerId, "TELEPORT", UndoReferenceBson.encodeBase64(
                request, "TELEPORT", Instant.parse("2026-06-10T02:00:00Z")));

        assertPoisonRowIsConsumedAndOlderRefIsReachable(
                stack, playerId, player, messages, "unknown mode TELEPORT");
    }
}
