package net.medievalrp.spyglass.plugin.command.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import net.medievalrp.spyglass.api.event.BlockSnapshot;
import net.medievalrp.spyglass.api.rollback.RollbackEffect;
import net.medievalrp.spyglass.api.rollback.RollbackResult;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.api.util.Duration;
import net.medievalrp.spyglass.plugin.config.SpyglassConfig;
import net.medievalrp.spyglass.plugin.rollback.RollbackEngine;
import net.medievalrp.spyglass.plugin.rollback.UndoStack;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

class UndoServiceTest {

    /**
     * Minimal {@link SpyglassConfig} for tests that just need the
     * batch-size and undo cap fields. Other limits are zeroed —
     * UndoService only reads {@code limits.rollbackBatchSize()}.
     */
    private static SpyglassConfig fakeConfig() {
        SpyglassConfig.Limits limits = new SpyglassConfig.Limits(
                250,         // maxRadius
                1_000,       // searchResult
                10_000,      // rollbackResult
                50,          // chatDump
                4_000,       // rollbackBatchSize
                Duration.parse("30s"),  // rollbackFlushTimeout
                20_000,      // rollbackPageSize
                5_000_000,   // rollbackUndoCap
                40L);        // rollbackTickBudgetMs
        return mock(SpyglassConfig.class, invocation -> {
            if ("limits".equals(invocation.getMethod().getName())) return limits;
            return null;
        });
    }

    @Test
    void rejectsNonPlayers() {
        UndoStack stack = mock(UndoStack.class);
        RollbackEngine engine = mock(RollbackEngine.class);
        CommandSender sender = mock(CommandSender.class);
        List<Component> messages = ServiceTestSupport.captureMessages(sender);

        new UndoService(engine, stack, ServiceSupport.synchronous(), fakeConfig()).execute(sender);

        assertThat(ServiceTestSupport.plainTexts(messages))
                .anyMatch(line -> line.contains("must be a player"));
        verifyNoInteractions(stack);
    }

    @Test
    void warnsWhenStackEmpty() {
        UndoStack stack = mock(UndoStack.class);
        RollbackEngine engine = mock(RollbackEngine.class);
        Player player = mock(Player.class);
        UUID id = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(id);
        when(stack.pop(id)).thenReturn(Optional.empty());
        List<Component> messages = ServiceTestSupport.captureMessages(player);

        new UndoService(engine, stack, ServiceSupport.synchronous(), fakeConfig()).execute(player);

        assertThat(ServiceTestSupport.plainTexts(messages))
                .anyMatch(line -> line.contains("no valid actions to undo"));
    }

    @Test
    void appliesInverseEffects() {
        UndoStack stack = mock(UndoStack.class);
        RollbackEngine engine = mock(RollbackEngine.class);
        Player player = mock(Player.class);
        UUID id = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(id);

        BlockLocation loc = new BlockLocation(UUID.randomUUID(), "world", 0, 64, 0);
        BlockSnapshot s = new BlockSnapshot(Material.STONE, "minecraft:stone",
                List.of(), List.of(), List.of(), List.of(), null);
        RollbackEffect effect = new RollbackEffect.BlockReplace(loc, s, s);
        UndoStack.UndoOperation op = new UndoStack.UndoOperation(
                UUID.randomUUID(), id, Instant.now(), "ROLLBACK", List.of(effect));
        when(stack.pop(id)).thenReturn(Optional.of(op));
        when(engine.applyAllChunked(ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), anyInt()))
                .thenReturn(CompletableFuture.completedFuture(
                        List.<RollbackResult>of(new RollbackResult.Applied(effect, effect))));
        List<Component> messages = ServiceTestSupport.captureMessages(player);

        new UndoService(engine, stack, ServiceSupport.synchronous(), fakeConfig()).execute(player);

        verify(engine).applyAllChunked(ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), anyInt());
        assertThat(ServiceTestSupport.plainTexts(messages))
                .anyMatch(line -> line.contains("1 reversals"));
    }
}
