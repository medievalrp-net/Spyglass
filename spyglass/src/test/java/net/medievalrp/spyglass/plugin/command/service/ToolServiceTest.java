package net.medievalrp.spyglass.plugin.command.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.medievalrp.spyglass.plugin.command.service.tool.ToolStateStore;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;

class ToolServiceTest {

    private static void emptyInventory(Player player) {
        PlayerInventory inv = mock(PlayerInventory.class);
        ItemStack air = mock(ItemStack.class);
        when(air.getType()).thenReturn(Material.AIR);
        when(inv.getContents()).thenReturn(new ItemStack[0]);
        when(inv.getItemInMainHand()).thenReturn(air);
        when(player.getInventory()).thenReturn(inv);
    }

    @Test
    void nonPlayerSenderRejected() {
        ToolStateStore store = mock(ToolStateStore.class);
        when(store.loadActive()).thenReturn(List.of());
        ToolService.WandHandout handout = mock(ToolService.WandHandout.class);
        CommandSender sender = mock(CommandSender.class);
        List<Component> captured = ServiceTestSupport.captureMessages(sender);

        new ToolService(store, Material.REDSTONE_LAMP, handout).toggle(sender);

        assertThat(ServiceTestSupport.plainTexts(captured))
                .anyMatch(line -> line.contains("non-players"));
        verify(store, never()).enable(any());
        verify(store, never()).disable(any());
        verify(handout, never()).give(any(), any());
        verify(handout, never()).take(any(), any());
    }

    @Test
    void togglingInactivePlayerActivatesAndGivesWand() {
        UUID id = UUID.randomUUID();
        ToolStateStore store = mock(ToolStateStore.class);
        when(store.loadActive()).thenReturn(List.of());
        ToolService.WandHandout handout = mock(ToolService.WandHandout.class);
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        emptyInventory(player);
        ServiceTestSupport.captureMessages(player);

        ToolService service = new ToolService(store, Material.REDSTONE_LAMP, handout);
        service.toggle(player);

        assertThat(service.isActive(id)).isTrue();
        verify(store).enable(id);
        verify(handout).give(player, Material.REDSTONE_LAMP);
    }

    @Test
    void toggleWhileActiveWithoutWandReissues() {
        UUID id = UUID.randomUUID();
        ToolStateStore store = mock(ToolStateStore.class);
        when(store.loadActive()).thenReturn(List.of(id));
        ToolService.WandHandout handout = mock(ToolService.WandHandout.class);
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        emptyInventory(player);
        List<Component> captured = ServiceTestSupport.captureMessages(player);

        ToolService service = new ToolService(store, Material.REDSTONE_LAMP, handout);
        assertThat(service.isActive(id)).isTrue();

        service.toggle(player);

        assertThat(service.isActive(id)).isTrue();
        verify(handout).give(player, Material.REDSTONE_LAMP);
        verify(store, never()).disable(id);
        assertThat(ServiceTestSupport.plainTexts(captured))
                .anyMatch(line -> line.contains("Added the v1 data tool"));
    }

    @Test
    void loadPersistsActiveAcrossRestart() {
        UUID id = UUID.randomUUID();
        ToolStateStore store = mock(ToolStateStore.class);
        when(store.loadActive()).thenReturn(List.of(id));

        ToolService service = new ToolService(store, Material.REDSTONE_LAMP, mock(ToolService.WandHandout.class));

        assertThat(service.isActive(id)).isTrue();
        assertThat(service.isActive(UUID.randomUUID())).isFalse();
    }

}
