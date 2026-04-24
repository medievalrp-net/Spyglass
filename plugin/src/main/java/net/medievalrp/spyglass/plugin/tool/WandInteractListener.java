package net.medievalrp.spyglass.plugin.tool;

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
import net.medievalrp.spyglass.plugin.command.service.SearchService;
import net.medievalrp.spyglass.plugin.command.service.ToolService;
import net.medievalrp.spyglass.plugin.config.SpyglassConfig;
import net.medievalrp.spyglass.plugin.util.BlockLocations;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

public final class WandInteractListener implements Listener {

    private final ToolService tool;
    private final SearchService search;
    private final SpyglassConfig config;
    private final Duration lookbackWindow;

    public WandInteractListener(ToolService tool, SearchService search, SpyglassConfig config) {
        this.tool = tool;
        this.search = search;
        this.config = config;
        this.lookbackWindow = Duration.parse("7d");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Player player = event.getPlayer();
        if (!tool.isActive(player.getUniqueId())) {
            return;
        }
        if (!isHoldingWand(event.getItem())) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }
        event.setCancelled(true);
        queryAt(player, block.getLocation());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (!tool.isActive(player.getUniqueId())) {
            return;
        }
        if (!isHoldingWand(player.getInventory().getItemInMainHand())) {
            return;
        }
        event.setCancelled(true);
        queryAt(player, event.getBlock().getLocation());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (!tool.isActive(player.getUniqueId())) {
            return;
        }
        if (!isHoldingWand(event.getItemInHand())) {
            return;
        }
        event.setCancelled(true);
        queryAt(player, event.getBlock().getLocation());
    }

    private boolean isHoldingWand(ItemStack stack) {
        return stack != null && stack.getType() == tool.wandMaterial() && stack.getType() != Material.AIR;
    }

    private void queryAt(Player player, Location location) {
        BlockLocation anchor = BlockLocations.fromLocation(location);
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
