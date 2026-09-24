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
}
