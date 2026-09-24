package net.medievalrp.spyglass.plugin.command.service.tool;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import net.medievalrp.spyglass.api.query.Flag;
import net.medievalrp.spyglass.api.query.QueryPredicate;
import net.medievalrp.spyglass.api.query.QueryRequest;
import net.medievalrp.spyglass.api.query.Sort;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.api.util.Duration;
import net.medievalrp.spyglass.plugin.command.param.RadiusParam;
import net.medievalrp.spyglass.plugin.command.render.Feedback;
import net.medievalrp.spyglass.plugin.command.service.SearchService;
import net.medievalrp.spyglass.plugin.command.service.ToolService;
import net.medievalrp.spyglass.plugin.config.SpyglassConfig;
import net.medievalrp.spyglass.plugin.util.BlockLocations;
import org.bukkit.Location;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.Event;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class WandInteractListener implements Listener {

    private final ToolService tool;
    private final SearchService search;
    private volatile SpyglassConfig config;


    public WandInteractListener(ToolService tool, SearchService search, SpyglassConfig config) {
        this.tool = tool;
        this.search = search;
        this.config = config;
        // tool.lookback, default 26w. The old hardcoded 7d silently hid
        // older history and read as "Spyglass cannot roll this back" (#271).

    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (!isHoldingWand(event.getItem())) {
            return;
        }
        event.setCancelled(true);
        event.setUseItemInHand(Event.Result.DENY);
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }
        if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
            denyAndSync(event, player, block);
            if (event.getHand() == EquipmentSlot.HAND) queryAt(player, block.getLocation());
            return;
        }
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            Block target = block.getRelative(event.getBlockFace());
            denyAndSync(event, player, block, target);
            if (event.getHand() == EquipmentSlot.HAND) queryAt(player, target.getLocation());
        }
    }

    private void denyAndSync(PlayerInteractEvent event, Player player, Block... blocks) {
        event.setCancelled(true);
        event.setUseInteractedBlock(Event.Result.DENY);
        event.setUseItemInHand(Event.Result.DENY);
        // Paper's prediction places the wand client-side before the server cancels;
        // re-send the true block state so the client's ghost block disappears.
        for (Block block : blocks) {
            player.sendBlockChange(block.getLocation(), block.getBlockData());
        }
        player.updateInventory();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (!isHoldingWand(player.getInventory().getItemInMainHand())) {
            return;
        }
        event.setCancelled(true);
        Block block = event.getBlock();
        player.sendBlockChange(block.getLocation(), block.getBlockData());
        queryAt(player, block.getLocation());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (!isHoldingWand(event.getItemInHand())) {
            return;
        }
        event.setCancelled(true);
        Block placed = event.getBlock();
        Block against = event.getBlockAgainst();
        player.sendBlockChange(placed.getLocation(), placed.getBlockData());
        player.sendBlockChange(against.getLocation(), against.getBlockData());
        player.updateInventory();
        queryAt(player, placed.getLocation());
    }

    private boolean isHoldingWand(ItemStack stack) {
        return net.medievalrp.spyglass.plugin.command.service.ToolService.WandHandout.isWandItem(stack);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityInteract(org.bukkit.event.player.PlayerInteractEntityEvent event) {
        ItemStack item = event.getHand() == EquipmentSlot.OFF_HAND
                ? event.getPlayer().getInventory().getItemInOffHand()
                : event.getPlayer().getInventory().getItemInMainHand();
        if (isHoldingWand(item)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCook(org.bukkit.event.block.BlockCookEvent event) {
        if (isHoldingWand(event.getSource())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(org.bukkit.event.player.PlayerDropItemEvent event) {
        if (isHoldingWand(event.getItemDrop().getItemStack())) event.setCancelled(true);
    }

    // Death/container drops must not feed a wand to item-consuming mobs (such
    // as sulfur cubes, whose stored block does not retain the item's PDC).
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemSpawn(org.bukkit.event.entity.ItemSpawnEvent event) {
        if (isHoldingWand(event.getEntity().getItemStack())) event.setCancelled(true);
    }

    // Conversion stations must never consume a tagged wand as an ordinary item.
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(org.bukkit.event.inventory.InventoryMoveItemEvent event) {
        if (isHoldingWand(event.getItem())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onClick(org.bukkit.event.inventory.InventoryClickEvent event) {
        if (!processing(event.getView().getTopInventory().getType())) return;
        ItemStack hotbar = event.getHotbarButton() >= 0
                ? event.getWhoClicked().getInventory().getItem(event.getHotbarButton()) : null;
        ItemStack offhand = event.getClick() == org.bukkit.event.inventory.ClickType.SWAP_OFFHAND
                ? event.getWhoClicked().getInventory().getItemInOffHand() : null;
        if (isHoldingWand(event.getCursor()) || isHoldingWand(event.getCurrentItem())
                || isHoldingWand(hotbar) || isHoldingWand(offhand)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrag(org.bukkit.event.inventory.InventoryDragEvent event) {
        if (processing(event.getView().getTopInventory().getType()) && isHoldingWand(event.getOldCursor())
                && event.getRawSlots().stream().anyMatch(slot -> slot < event.getView().getTopInventory().getSize())) {
            event.setCancelled(true);
        }
    }

    private static boolean processing(org.bukkit.event.inventory.InventoryType type) {
        return switch (type) {
            case ANVIL, WORKBENCH, CRAFTING, CRAFTER, FURNACE, BLAST_FURNACE, SMOKER,
                    GRINDSTONE, SMITHING, STONECUTTER, LOOM, ENCHANTING, BREWING, BEACON, CARTOGRAPHY -> true;
            default -> false;
        };
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAnvil(PrepareAnvilEvent event) {
        if (isHoldingWand(event.getInventory().getItem(0))
                || isHoldingWand(event.getInventory().getItem(1))) {
            event.setResult(null);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCraft(PrepareItemCraftEvent event) {
        for (ItemStack ingredient : event.getInventory().getMatrix()) {
            if (isHoldingWand(ingredient)) {
                event.getInventory().setResult(null);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent event) {
        if (isHoldingWand(event.getItem())) event.setCancelled(true);
    }

    public void setConfig(SpyglassConfig config) { this.config = config; }

    private void queryAt(Player player, Location location) {
        Duration lookbackWindow = config.tool().lookback();
        if (!tool.isActive(player.getUniqueId()) || !player.hasPermission("spyglass.tool")) return;
        BlockLocation anchor = BlockLocations.fromLocation(location);
        String target = location.getBlock().getType().name();
        player.sendMessage(Feedback.inspectHeader(target, anchor, lookbackWindow));
        List<QueryPredicate> predicates = new ArrayList<>();
        predicates.add(RadiusParam.groupAround(anchor, 0));
        predicates.add(new QueryPredicate.Range(
                "occurred", lookbackWindow.before(Instant.now()), null));
        QueryRequest request = new QueryRequest(
                predicates, Sort.NEWEST_FIRST,
                config.limits().searchResult(),
                EnumSet.of(Flag.NO_GROUP),
                false);
        search.executeRequest(player, request);
    }
}
