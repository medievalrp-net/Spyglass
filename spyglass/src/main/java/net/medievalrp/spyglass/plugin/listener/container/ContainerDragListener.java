package net.medievalrp.spyglass.plugin.listener.container;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import net.medievalrp.spyglass.api.event.ContainerDepositRecord;
import net.medievalrp.spyglass.api.event.StoredItem;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.plugin.listener.RecordingSupport;
import net.medievalrp.spyglass.plugin.listener.RecordingListener;
import net.medievalrp.spyglass.plugin.pipeline.Recorder;
import net.medievalrp.spyglass.plugin.util.BlockLocations;
import net.medievalrp.spyglass.plugin.util.ItemSerialization;
import org.bukkit.Material;
import org.bukkit.block.Container;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class ContainerDragListener implements RecordingListener {

    private final Recorder recorder;
    private final RecordingSupport support;

    public ContainerDragListener(Recorder recorder, RecordingSupport support) {
        this.recorder = recorder;
        this.support = support;
    }

    @Override
    public Set<String> events() {
        return Set.of("deposit");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        Inventory top = event.getView().getTopInventory();
        InventoryHolder holder = top.getHolder();
        if (!(holder instanceof Container container)) {
            return;
        }
        if (holder instanceof ShulkerBox) {
            return;
        }
        int topSize = top.getSize();
        BlockLocation location = BlockLocations.fromLocation(container.getBlock().getLocation());
        String containerType = container.getBlock().getType().name();
        Instant occurred = support.now();
        for (Map.Entry<Integer, ItemStack> entry : event.getNewItems().entrySet()) {
            int rawSlot = entry.getKey();
            if (rawSlot >= topSize) {
                continue;
            }
            ItemStack deposited = entry.getValue();
            if (deposited == null || deposited.getType() == Material.AIR) {
                continue;
            }
            ItemStack existing = top.getItem(rawSlot);
            int existingAmount = existing == null || existing.getType() == Material.AIR ? 0 : existing.getAmount();
            int delta = deposited.getAmount() - existingAmount;
            if (delta <= 0) {
                continue;
            }
            StoredItem storedBefore = existingAmount > 0 ? ItemSerialization.storedItem(rawSlot, existing) : null;
            StoredItem storedAfter = ItemSerialization.storedItem(rawSlot, deposited);
            recorder.record(new ContainerDepositRecord(
                    support.newId(), 1, "deposit", occurred,
                    support.expiresAt(occurred),
                    support.playerOrigin(), support.playerSource(player),
                    location, deposited.getType().name(), containerType,
                    rawSlot, delta, storedBefore, storedAfter));
        }
    }
}
