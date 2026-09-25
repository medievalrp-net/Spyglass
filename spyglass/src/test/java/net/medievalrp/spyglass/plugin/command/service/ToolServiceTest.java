package net.medievalrp.spyglass.plugin.command.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
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
    void toggleWhileActiveWithoutWandDeactivates() {
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

        assertThat(service.isActive(id)).isFalse();
        verify(handout, never()).give(player, Material.REDSTONE_LAMP);
        verify(handout).take(player, Material.REDSTONE_LAMP);
        verify(store).disable(id);
        assertThat(ServiceTestSupport.plainTexts(captured))
                .anyMatch(line -> line.contains("Deactivated the Spyglass Data Tool"));
    }

    @Test
    void storeWriteIsDeferredOffTheMainThread() {
        // #151: store.enable/disable do blocking I/O (Mongo/SQLite/ClickHouse)
        // and must not run on the command (main) thread. The in-memory toggle
        // is instant; the persist is queued to the async pool.
        UUID id = UUID.randomUUID();
        ToolStateStore store = mock(ToolStateStore.class);
        when(store.loadActive()).thenReturn(List.of());
        ToolService.WandHandout handout = mock(ToolService.WandHandout.class);
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        emptyInventory(player);
        ServiceTestSupport.captureMessages(player);
        ServiceTestSupport.RecordingSupport support = new ServiceTestSupport.RecordingSupport();

        ToolService service = new ToolService(store, Material.REDSTONE_LAMP, handout, support,
                Logger.getLogger("tool-test"));
        service.toggle(player);

        // Activation is reflected immediately, but the store write has NOT run
        // yet - it is sitting on the async queue, off the main thread.
        assertThat(service.isActive(id)).isTrue();
        verify(store, never()).enable(any());

        support.drain();
        verify(store).enable(id);
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

    private static ItemStack taggedItem() {
        ItemStack item = mock(ItemStack.class);
        var meta = mock(org.bukkit.inventory.meta.ItemMeta.class);
        var data = mock(org.bukkit.persistence.PersistentDataContainer.class);
        when(item.hasItemMeta()).thenReturn(true);
        when(item.getItemMeta()).thenReturn(meta);
        when(meta.getPersistentDataContainer()).thenReturn(data);
        when(data.has(ToolService.WAND_KEY, org.bukkit.persistence.PersistentDataType.BYTE)).thenReturn(true);
        return item;
    }

    @Test
    void deactivationRemovesEveryTaggedItemAndCursorButPreservesOrdinaryItems() {
        UUID id = UUID.randomUUID();
        ToolStateStore store = mock(ToolStateStore.class);
        when(store.loadActive()).thenReturn(List.of(id));
        Player player = mock(Player.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(player.getUniqueId()).thenReturn(id);
        when(player.getInventory()).thenReturn(inventory);
        ItemStack wand = taggedItem(), oldMaterial = taggedItem(), armor = taggedItem();
        ItemStack ordinary = mock(ItemStack.class);
        when(ordinary.getType()).thenReturn(Material.REDSTONE_LAMP);
        ItemStack[] contents = new ItemStack[41];
        contents[0] = wand; contents[8] = wand; contents[9] = ordinary;
        contents[39] = armor; contents[40] = oldMaterial;
        when(inventory.getContents()).thenReturn(contents);
        when(inventory.getItemInMainHand()).thenReturn(ordinary);
        when(inventory.firstEmpty()).thenReturn(-1);
        ItemStack cursor = taggedItem();
        when(player.getItemOnCursor()).thenReturn(cursor);
        ServiceTestSupport.captureMessages(player);
        ToolService service = new ToolService(store, Material.GLOWSTONE, ToolService.WandHandout.bukkit());

        service.toggle(player);

        assertThat(service.isActive(id)).isFalse();
        verify(store).disable(id);
        for (int slot : new int[] {0, 8, 39, 40}) verify(inventory).setItem(slot, null);
        verify(inventory, never()).setItem(org.mockito.ArgumentMatchers.eq(9), any());
        verify(player).setItemOnCursor(null);
    }

    @Test
    void removalPreservesAnUntaggedCursorItem() {
        Player player = mock(Player.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.getContents()).thenReturn(new ItemStack[41]);
        when(player.getItemOnCursor()).thenReturn(mock(ItemStack.class));
        ToolService.WandHandout.bukkit().take(player, Material.REDSTONE_LAMP);
        verify(player, never()).setItemOnCursor(any());
    }

}
