package net.medievalrp.spyglass.plugin.command.service.tool;

import static org.mockito.Mockito.*;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import net.medievalrp.spyglass.api.util.Duration;
import net.medievalrp.spyglass.plugin.config.SpyglassConfig;
import net.medievalrp.spyglass.plugin.command.service.SearchService;
import net.medievalrp.spyglass.plugin.command.service.ToolService;
import org.junit.jupiter.api.Test;

class WandProtectionTest {
    private ItemStack wand() {
        ItemStack item = mock(ItemStack.class);
        ItemMeta meta = mock(ItemMeta.class);
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        when(item.hasItemMeta()).thenReturn(true);
        when(item.getItemMeta()).thenReturn(meta);
        when(meta.getPersistentDataContainer()).thenReturn(pdc);
        when(pdc.has(ToolService.WAND_KEY, PersistentDataType.BYTE)).thenReturn(true);
        when(item.getType()).thenReturn(Material.REDSTONE_LAMP);
        return item;
    }
    private WandInteractListener listener(SearchService search) {
        SpyglassConfig config = mock(SpyglassConfig.class);
        when(config.tool()).thenReturn(new SpyglassConfig.Tool(Material.GLOWSTONE, Duration.parse("26w")));
        return new WandInteractListener(mock(ToolService.class), search, config);
    }
    @Test void inactiveOldMaterialWandCannotBecomeAPlacedBlock() {
        SearchService search = mock(SearchService.class);
        BlockPlaceEvent event = mock(BlockPlaceEvent.class);
        Player player = mock(Player.class);
        when(event.getPlayer()).thenReturn(player);
        ItemStack item = wand();
        when(event.getItemInHand()).thenReturn(item);
        when(event.getBlock()).thenReturn(mock(Block.class));
        when(event.getBlockAgainst()).thenReturn(mock(Block.class));
        listener(search).onPlace(event);
        verify(event).setCancelled(true);
        verifyNoInteractions(search);
    }
    @Test void ordinaryLampIsNotProtected() {
        BlockPlaceEvent event = mock(BlockPlaceEvent.class);
        when(event.getItemInHand()).thenReturn(mock(ItemStack.class));
        listener(mock(SearchService.class)).onPlace(event);
        verify(event, never()).setCancelled(true);
    }
    @Test void anvilCannotRenameInactiveWand() {
        PrepareAnvilEvent event = mock(PrepareAnvilEvent.class);
        AnvilInventory inventory = mock(AnvilInventory.class);
        when(event.getInventory()).thenReturn(inventory);
        ItemStack item = wand();
        when(inventory.getItem(0)).thenReturn(item);
        listener(mock(SearchService.class)).onAnvil(event);
        verify(event).setResult(null);
    }
    @Test void droppingTaggedToolConsumesItAndDeactivatesWithoutReturningItToInventory() {
        ToolService tool = mock(ToolService.class);
        var listener = new WandInteractListener(tool, mock(SearchService.class), mock(SpyglassConfig.class));
        var event = mock(org.bukkit.event.player.PlayerDropItemEvent.class);
        var dropped = mock(org.bukkit.entity.Item.class);
        var player = mock(Player.class);
        ItemStack item = wand();
        when(event.getItemDrop()).thenReturn(dropped);
        when(event.getPlayer()).thenReturn(player);
        when(dropped.getItemStack()).thenReturn(item);
        listener.onDrop(event);
        verify(dropped).remove();
        verify(tool).deactivate(player);
        verify(event, never()).setCancelled(true);
    }

    @Test void cancelledOrOrdinaryDropsDoNotDeactivateInspection() {
        ToolService tool = mock(ToolService.class);
        var listener = new WandInteractListener(tool, mock(SearchService.class), mock(SpyglassConfig.class));
        var cancelled = mock(org.bukkit.event.player.PlayerDropItemEvent.class);
        when(cancelled.isCancelled()).thenReturn(true);
        listener.onDrop(cancelled);
        var ordinary = mock(org.bukkit.event.player.PlayerDropItemEvent.class);
        var dropped = mock(org.bukkit.entity.Item.class);
        when(ordinary.getItemDrop()).thenReturn(dropped);
        when(dropped.getItemStack()).thenReturn(mock(ItemStack.class));
        listener.onDrop(ordinary);
        verifyNoInteractions(tool);
        verify(dropped, never()).remove();
    }

    @Test void inventoryWindowThrowsReachTheDropHandler() {
        for (var action : new org.bukkit.event.inventory.InventoryAction[] {
                org.bukkit.event.inventory.InventoryAction.DROP_ALL_CURSOR,
                org.bukkit.event.inventory.InventoryAction.DROP_ONE_CURSOR,
                org.bukkit.event.inventory.InventoryAction.DROP_ALL_SLOT,
                org.bukkit.event.inventory.InventoryAction.DROP_ONE_SLOT}) {
            var event = mock(org.bukkit.event.inventory.InventoryClickEvent.class);
            when(event.getAction()).thenReturn(action);
            listener(mock(SearchService.class)).onClick(event);
            verify(event, never()).setCancelled(true);
        }
    }

}
