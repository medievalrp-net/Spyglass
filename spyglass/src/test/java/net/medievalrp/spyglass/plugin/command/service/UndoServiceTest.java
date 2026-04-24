package net.medievalrp.spyglass.plugin.command.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.medievalrp.spyglass.api.event.BlockSnapshot;
import net.medievalrp.spyglass.api.rollback.RollbackEffect;
import net.medievalrp.spyglass.api.rollback.RollbackResult;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.plugin.rollback.RollbackEngine;
import net.medievalrp.spyglass.plugin.rollback.UndoStack;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

class UndoServiceTest {

    @Test
    void rejectsNonPlayers() {
        UndoStack stack = mock(UndoStack.class);
        RollbackEngine engine = mock(RollbackEngine.class);
        CommandSender sender = mock(CommandSender.class);
        List<Component> messages = ServiceTestSupport.captureMessages(sender);

        new UndoService(engine, stack, ServiceSupport.synchronous()).execute(sender);

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

        new UndoService(engine, stack, ServiceSupport.synchronous()).execute(player);

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
        when(engine.applyAll(ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(List.of(new RollbackResult.Applied(effect, effect)));
        List<Component> messages = ServiceTestSupport.captureMessages(player);

        new UndoService(engine, stack, ServiceSupport.synchronous()).execute(player);

        verify(engine).applyAll(ArgumentMatchers.any(), ArgumentMatchers.any());
        assertThat(ServiceTestSupport.plainTexts(messages))
                .anyMatch(line -> line.contains("1 reversals"));
    }
}
