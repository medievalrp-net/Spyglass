package net.medievalrp.spyglass.plugin.listener.container;

import java.util.Set;
import net.medievalrp.spyglass.api.event.ContainerInteractRecord;
import net.medievalrp.spyglass.api.event.RecordContext;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.plugin.listener.RecordingListener;
import net.medievalrp.spyglass.plugin.listener.RecordingSupport;
import net.medievalrp.spyglass.plugin.pipeline.Recorder;
import net.medievalrp.spyglass.plugin.util.BlockLocations;
import org.bukkit.block.Container;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.ApiStatus;

/**
 * Logs container open/close interactions. Regular containers emit {@code open}
 * only; shulkers emit both {@code shulker-open} and {@code shulker-close} to
 * mirror v1's behavior.
 */
@ApiStatus.Internal
public final class ContainerInteractListener implements RecordingListener {

    private final Recorder recorder;
    private final RecordingSupport support;

    public ContainerInteractListener(Recorder recorder, RecordingSupport support) {
        this.recorder = recorder;
        this.support = support;
    }

    @Override
    public Set<String> events() {
        return Set.of("open", "shulker-open", "shulker-close");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        Inventory inv = event.getInventory();
        InventoryHolder holder = inv.getHolder();
        if (!(holder instanceof Container container)) {
            return;
        }
        String event_ = holder instanceof ShulkerBox ? "shulker-open" : "open";
        BlockLocation location = BlockLocations.fromLocation(container.getBlock().getLocation());
        String target = container.getBlock().getType().name();
        RecordContext ctx = support.playerContext(player, location);
        recorder.record(ContainerInteractRecord.of(ctx, event_, target));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        Inventory inv = event.getInventory();
        InventoryHolder holder = inv.getHolder();
        if (!(holder instanceof ShulkerBox shulker)) {
            return;
        }
        BlockLocation location = BlockLocations.fromLocation(shulker.getBlock().getLocation());
        String target = shulker.getBlock().getType().name();
        RecordContext ctx = support.playerContext(player, location);
        recorder.record(ContainerInteractRecord.of(ctx, "shulker-close", target));
    }
}
