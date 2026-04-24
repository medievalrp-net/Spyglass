package net.medievalrp.spyglass.plugin.listener.modern;

import java.time.Instant;
import java.util.Set;
import net.medievalrp.spyglass.api.event.ContainerDepositRecord;
import net.medievalrp.spyglass.api.event.ContainerWithdrawRecord;
import net.medievalrp.spyglass.api.event.StoredItem;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.plugin.listener.RecordingSupport;
import net.medievalrp.spyglass.plugin.listener.RecordingListener;
import net.medievalrp.spyglass.plugin.pipeline.Recorder;
import net.medievalrp.spyglass.plugin.util.BlockLocations;
import net.medievalrp.spyglass.plugin.util.InventoryActions;
import net.medievalrp.spyglass.plugin.util.InventoryActions.Direction;
import net.medievalrp.spyglass.plugin.util.ItemSerialization;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class ShulkerTransactionListener implements RecordingListener {

    private final Recorder recorder;
    private final RecordingSupport support;

    public ShulkerTransactionListener(Recorder recorder, RecordingSupport support) {
        this.recorder = recorder;
        this.support = support;
    }

    @Override
    public Set<String> events() {
        return Set.of("shulker-deposit", "shulker-withdraw");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        Inventory clicked = event.getClickedInventory();
        if (clicked == null) {
            return;
        }
        InventoryHolder holder = clicked.getHolder();
        if (!(holder instanceof ShulkerBox shulker)) {
            return;
        }

        InventoryAction action = event.getAction();
        Direction direction = InventoryActions.directionOf(action);
        if (direction == null) {
            return;
        }
        int slot = event.getSlot();
        ItemStack slotItem = clicked.getItem(slot);
        ItemStack cursor = event.getCursor();
        int amount = InventoryActions.amountOf(action, slotItem, cursor);
        if (amount <= 0) {
            return;
        }

        BlockLocation location = BlockLocations.fromLocation(shulker.getBlock().getLocation());
        String containerType = shulker.getBlock().getType().name();
        StoredItem before = ItemSerialization.storedItem(slot, slotItem);
        Instant occurred = support.now();

        switch (direction) {
            case DEPOSIT -> recorder.record(new ContainerDepositRecord(
                    support.newId(), 1, "shulker-deposit", occurred,
                    support.expiresAt(occurred),
                    support.playerOrigin(), support.playerSource(player),
                    location, cursor == null ? "UNKNOWN" : cursor.getType().name(),
                    containerType, slot, amount, before, null));
            case WITHDRAW -> recorder.record(new ContainerWithdrawRecord(
                    support.newId(), 1, "shulker-withdraw", occurred,
                    support.expiresAt(occurred),
                    support.playerOrigin(), support.playerSource(player),
                    location, slotItem == null ? "UNKNOWN" : slotItem.getType().name(),
                    containerType, slot, amount, before, null));
        }
    }
}
