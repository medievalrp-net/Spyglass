package net.medievalrp.spyglass.plugin.listener.container;

import java.util.Set;
import net.medievalrp.spyglass.api.event.ContainerInteractRecord;
import net.medievalrp.spyglass.api.event.RecordContext;
import net.medievalrp.spyglass.plugin.listener.RecordingListener;
import net.medievalrp.spyglass.plugin.listener.RecordingSupport;
import net.medievalrp.spyglass.plugin.pipeline.Recorder;
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
 * Logs container open/close interactions. Both kinds of containers
 * emit a matching close event — shulkers as {@code shulker-close} to
 * preserve v1's distinct event name, every other container as
 * {@code close}.
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
        return Set.of("open", "close", "shulker-open", "shulker-close");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        Inventory inv = event.getInventory();
        InventoryHolder holder = inv.getHolder();
        ContainerHolders.Target target = ContainerHolders.resolve(holder);
        if (target == null) {
            return;
        }
        String event_ = holder instanceof ShulkerBox ? "shulker-open" : "open";
        RecordContext ctx = support.playerContext(player, target.location());
        recorder.record(ContainerInteractRecord.of(ctx, event_, target.type()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        Inventory inv = event.getInventory();
        InventoryHolder holder = inv.getHolder();
        ContainerHolders.Target target = ContainerHolders.resolve(holder);
        if (target == null) {
            return;
        }
        String event_ = holder instanceof ShulkerBox ? "shulker-close" : "close";
        RecordContext ctx = support.playerContext(player, target.location());
        recorder.record(ContainerInteractRecord.of(ctx, event_, target.type()));
    }
}
