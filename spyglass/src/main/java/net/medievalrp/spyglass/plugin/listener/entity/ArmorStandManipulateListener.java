package net.medievalrp.spyglass.plugin.listener.entity;

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
import net.medievalrp.spyglass.plugin.util.ItemSerialization;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class ArmorStandManipulateListener implements RecordingListener {

    private final Recorder recorder;
    private final RecordingSupport support;

    public ArmorStandManipulateListener(Recorder recorder, RecordingSupport support) {
        this.recorder = recorder;
        this.support = support;
    }

    @Override
    public Set<String> events() {
        return Set.of("entity-deposit", "entity-withdraw");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onArmorStandManipulate(PlayerArmorStandManipulateEvent event) {
        ItemStack playerItem = event.getPlayerItem();
        ItemStack armorStandItem = event.getArmorStandItem();
        boolean playerPlacing = !isEmpty(playerItem);
        boolean standHas = !isEmpty(armorStandItem);
        if (!playerPlacing && !standHas) {
            return;
        }

        int slot = slotFor(event.getSlot());
        BlockLocation location = BlockLocations.fromLocation(event.getRightClicked().getLocation());
        Instant occurred = support.now();
        String containerType = "ARMOR_STAND";
        StoredItem beforeItem = ItemSerialization.storedItem(slot, armorStandItem);
        StoredItem afterItem = ItemSerialization.storedItem(slot, playerItem);

        if (standHas && playerPlacing) {
            // Swap: report the withdraw half only for brevity (armor stand loses armorStandItem,
            // player's item swaps in). Deposit side would double-count a single click.
            recorder.record(new ContainerWithdrawRecord(
                    support.newId(), 1, "entity-withdraw",
                    occurred, support.expiresAt(occurred),
                    support.playerOrigin(), support.playerSource(event.getPlayer()),
                    location, armorStandItem.getType().name(),
                    containerType, slot,
                    armorStandItem.getAmount(), beforeItem, afterItem));
            return;
        }
        if (standHas) {
            recorder.record(new ContainerWithdrawRecord(
                    support.newId(), 1, "entity-withdraw",
                    occurred, support.expiresAt(occurred),
                    support.playerOrigin(), support.playerSource(event.getPlayer()),
                    location, armorStandItem.getType().name(),
                    containerType, slot,
                    armorStandItem.getAmount(), beforeItem, null));
            return;
        }
        recorder.record(new ContainerDepositRecord(
                support.newId(), 1, "entity-deposit",
                occurred, support.expiresAt(occurred),
                support.playerOrigin(), support.playerSource(event.getPlayer()),
                location, playerItem.getType().name(),
                containerType, slot,
                playerItem.getAmount(), null, afterItem));
    }

    private static boolean isEmpty(ItemStack stack) {
        return stack == null || stack.getType() == Material.AIR;
    }

    private static int slotFor(org.bukkit.inventory.EquipmentSlot slot) {
        return switch (slot) {
            case HEAD -> 39;
            case CHEST -> 38;
            case LEGS -> 37;
            case FEET -> 36;
            case HAND -> 0;
            case OFF_HAND -> 40;
            default -> 0;
        };
    }
}
