package net.medievalrp.spyglass.plugin.command.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import net.medievalrp.spyglass.api.event.BlockSnapshot;
import net.medievalrp.spyglass.api.query.Flag;
import net.medievalrp.spyglass.api.query.QueryPredicate;
import net.medievalrp.spyglass.api.query.QueryRequest;
import net.medievalrp.spyglass.api.query.Sort;
import net.medievalrp.spyglass.api.rollback.RollbackEffect;
import net.medievalrp.spyglass.api.rollback.RollbackResult;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.api.util.Duration;
import net.medievalrp.spyglass.plugin.config.SpyglassConfig;
import net.medievalrp.spyglass.plugin.rollback.RollbackEngine;
import net.medievalrp.spyglass.plugin.rollback.UndoStack;
import net.medievalrp.spyglass.plugin.storage.UndoReferenceBson;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;

class UndoServiceTest {

    /**
     * Minimal {@link SpyglassConfig} for tests that just need the
     * batch-size field — the legacy replay path is its only consumer.
     */
    private static SpyglassConfig fakeConfig() {
        SpyglassConfig.Limits limits = new SpyglassConfig.Limits(
                250,         // maxRadius
                1_000,       // searchResult
                50,          // chatDump
                4_000,       // rollbackBatchSize
                Duration.parse("30s"),  // rollbackFlushTimeout
                40L);        // rollbackTickBudgetMs
        return mock(SpyglassConfig.class, invocation -> {
            if ("limits".equals(invocation.getMethod().getName())) return limits;
            return null;
        });
    }

    /** In-memory reference op recording tombstone/close calls. */
    private static final class FakeReference implements UndoStack.ReplayReference {
        private final String blob;
        boolean tombstoned = false;
        boolean closed = false;

        FakeReference(String blob) {
            this.blob = blob;
        }

        @Override
        public UUID operationId() {
            return UUID.fromString("00000000-0000-0000-0000-000000000001");
        }

        @Override
        public Instant createdAt() {
            return Instant.EPOCH;
        }

        @Override
        public String operationType() {
            return "ROLLBACK";
        }

        @Override
        public String referenceBase64() {
            return blob;
        }

        @Override
        public void tombstone() {
            tombstoned = true;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    /** In-memory legacy op over fixed chunks, recording tombstone. */
    private static final class FakeLegacy implements UndoStack.LegacyOperation {
        private final List<List<RollbackEffect>> chunks;
        private int next = 0;
        boolean tombstoned = false;
        boolean closed = false;

        FakeLegacy(List<List<RollbackEffect>> chunks) {
            this.chunks = new ArrayList<>(chunks);
        }

        @Override
        public UUID operationId() {
            return UUID.fromString("00000000-0000-0000-0000-000000000002");
        }

        @Override
        public Instant createdAt() {
            return Instant.EPOCH;
        }

        @Override
        public String operationType() {
            return "ROLLBACK";
        }

        @Override
        public int chunkCount() {
            return chunks.size();
        }

        @Override
        public Optional<List<RollbackEffect>> nextChunk() {
            if (next >= chunks.size()) {
                return Optional.empty();
            }
            return Optional.of(chunks.get(next++));
        }

        @Override
        public void tombstone() {
            tombstoned = true;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static RollbackEffect blockEffect() {
        BlockLocation loc = new BlockLocation(UUID.randomUUID(), "world", 0, 64, 0);
        BlockSnapshot s = new BlockSnapshot(Material.STONE, "minecraft:stone",
                List.of(), List.of(), List.of(), List.of(), null);
        return new RollbackEffect.BlockReplace(loc, s, s);
    }

    @Test
    void rejectsNonPlayers() {
        UndoStack stack = mock(UndoStack.class);
        RollbackEngine engine = mock(RollbackEngine.class);
        RollbackService rollbacks = mock(RollbackService.class);
        CommandSender sender = mock(CommandSender.class);
        List<Component> messages = ServiceTestSupport.captureMessages(sender);

        new UndoService(engine, stack, ServiceSupport.synchronous(), fakeConfig(), rollbacks, null)
                .execute(sender);

        assertThat(ServiceTestSupport.plainTexts(messages))
                .anyMatch(line -> line.contains("must be a player"));
        verifyNoInteractions(stack);
    }

    @Test
    void warnsWhenStackEmpty() {
        UndoStack stack = mock(UndoStack.class);
        RollbackEngine engine = mock(RollbackEngine.class);
        RollbackService rollbacks = mock(RollbackService.class);
        Player player = mock(Player.class);
        UUID id = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(id);
        when(stack.openLatest(id)).thenReturn(Optional.empty());
        List<Component> messages = ServiceTestSupport.captureMessages(player);

        new UndoService(engine, stack, ServiceSupport.synchronous(), fakeConfig(), rollbacks, null)
                .execute(player);

        assertThat(ServiceTestSupport.plainTexts(messages))
                .anyMatch(line -> line.contains("no valid actions to undo"));
    }

    @Test
    void replaysReferenceInOppositeModeWithCeilingAndTombstonesOnDone() {
        UndoStack stack = mock(UndoStack.class);
        RollbackEngine engine = mock(RollbackEngine.class);
        RollbackService rollbacks = mock(RollbackService.class);
        Player player = mock(Player.class);
        UUID id = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(id);

        Instant ceiling = Instant.parse("2026-06-10T02:00:00Z");
        QueryRequest stored = new QueryRequest(
                List.of(new QueryPredicate.Eq("source.playerId", id)),
                Sort.NEWEST_FIRST, 123, EnumSet.of(Flag.NO_GROUP), false);
        FakeReference ref = new FakeReference(
                UndoReferenceBson.encodeBase64(stored, "ROLLBACK", ceiling));
        when(stack.openLatest(id)).thenReturn(Optional.of(ref));
        ServiceTestSupport.captureMessages(player);

        new UndoService(engine, stack, ServiceSupport.synchronous(), fakeConfig(), rollbacks, null)
                .execute(player);

        ArgumentCaptor<QueryRequest> request = ArgumentCaptor.forClass(QueryRequest.class);
        ArgumentCaptor<RollbackMode> mode = ArgumentCaptor.forClass(RollbackMode.class);
        ArgumentCaptor<Runnable> onDone = ArgumentCaptor.forClass(Runnable.class);
        verify(rollbacks).executeReplay(eq(player), request.capture(), mode.capture(),
                any(String.class), org.mockito.ArgumentMatchers.anyMap(), onDone.capture());

        // Opposite direction of the stored op.
        assertThat(mode.getValue()).isEqualTo(RollbackMode.RESTORE);
        // Original predicates survive, plus the occurred ceiling.
        assertThat(request.getValue().predicates())
                .contains(new QueryPredicate.Eq("source.playerId", id))
                .contains(new QueryPredicate.Range("occurred", Instant.EPOCH, ceiling));
        assertThat(request.getValue().limit()).isEqualTo(123);

        // The reference is consumed only when the replay reports done.
        assertThat(ref.tombstoned).isFalse();
        onDone.getValue().run();
        assertThat(ref.tombstoned).isTrue();
        assertThat(ref.closed).isTrue();
        verifyNoInteractions(engine); // reference path never applies directly
    }

    @Test
    void legacyOperationReplaysChunksAndTombstonesOnce() {
        UndoStack stack = mock(UndoStack.class);
        RollbackEngine engine = mock(RollbackEngine.class);
        RollbackService rollbacks = mock(RollbackService.class);
        Player player = mock(Player.class);
        UUID id = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(id);

        RollbackEffect a = blockEffect();
        RollbackEffect b = blockEffect();
        FakeLegacy legacy = new FakeLegacy(List.of(List.of(a), List.of(b)));
        when(stack.openLatest(id)).thenReturn(Optional.of(legacy));
        when(engine.applyAllChunked(ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), anyInt()))
                .thenAnswer(invocation -> {
                    List<RollbackEffect> effects = invocation.getArgument(0);
                    assertThat(effects).as("one ledger chunk per apply").hasSize(1);
                    return CompletableFuture.completedFuture(List.<RollbackResult>of(
                            new RollbackResult.Applied(effects.get(0), effects.get(0))));
                });
        List<Component> messages = ServiceTestSupport.captureMessages(player);

        new UndoService(engine, stack, ServiceSupport.synchronous(), fakeConfig(), rollbacks, null)
                .execute(player);

        verify(engine, times(2)).applyAllChunked(ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), anyInt());
        assertThat(ServiceTestSupport.plainTexts(messages))
                .anyMatch(line -> line.contains("2 reversals"));
        assertThat(legacy.tombstoned).isTrue();
        assertThat(legacy.closed).isTrue();
        verifyNoInteractions(rollbacks);
    }

    @Test
    void failedLegacyReplayLeavesOperationPoppable() {
        UndoStack stack = mock(UndoStack.class);
        RollbackEngine engine = mock(RollbackEngine.class);
        RollbackService rollbacks = mock(RollbackService.class);
        Player player = mock(Player.class);
        UUID id = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(id);

        FakeLegacy legacy = new FakeLegacy(List.of(List.of(blockEffect())));
        when(stack.openLatest(id)).thenReturn(Optional.of(legacy));
        when(engine.applyAllChunked(ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), anyInt()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("boom")));
        List<Component> messages = ServiceTestSupport.captureMessages(player);

        new UndoService(engine, stack, ServiceSupport.synchronous(), fakeConfig(), rollbacks, null)
                .execute(player);

        assertThat(ServiceTestSupport.plainTexts(messages))
                .anyMatch(line -> line.contains("Undo failed"));
        assertThat(legacy.tombstoned)
                .as("failed replay must NOT consume the op").isFalse();
        assertThat(legacy.closed).isTrue();
    }
}
