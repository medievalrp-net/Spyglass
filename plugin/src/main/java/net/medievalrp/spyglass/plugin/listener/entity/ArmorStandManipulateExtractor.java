package net.medievalrp.spyglass.plugin.listener.entity;

import java.time.Instant;
import java.util.stream.Stream;
import net.medievalrp.spyglass.api.event.ContainerDepositRecord;
import net.medievalrp.spyglass.api.event.ContainerWithdrawRecord;
import net.medievalrp.spyglass.api.event.EventRecord;
import net.medievalrp.spyglass.api.event.StoredItem;
import net.medievalrp.spyglass.api.extension.EventExtractor;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.plugin.listener.ExtractorSupport;
import net.medievalrp.spyglass.plugin.util.BlockLocations;
import net.medievalrp.spyglass.plugin.util.ItemSerialization;
import org.bukkit.Material;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.inventory.ItemStack;

public final class ArmorStandManipulateExtractor implements EventExtractor<PlayerArmorStandManipulateEvent, EventRecord> {

    private final ExtractorSupport support;

    public ArmorStandManipulateExtractor(ExtractorSupport support) {
        this.support = support;
    }

    @Override
    public Class<PlayerArmorStandManipulateEvent> eventType() {
        return PlayerArmorStandManipulateEvent.class;
    }

    @Override
    public Stream<EventRecord> extract(PlayerArmorStandManipulateEvent event) {
        ItemStack playerItem = event.getPlayerItem();
        ItemStack armorStandItem = event.getArmorStandItem();
        boolean playerPlacing = !isEmpty(playerItem);
        boolean standHas = !isEmpty(armorStandItem);
        if (!playerPlacing && !standHas) {
            return Stream.empty();
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
            return Stream.of(new ContainerWithdrawRecord(
                    support.newId(), 1, "entity-withdraw",
                    occurred, support.expiresAt(occurred),
                    support.playerOrigin(), support.playerSource(event.getPlayer()),
                    location, armorStandItem.getType().name(),
                    containerType, slot,
                    armorStandItem.getAmount(), beforeItem, afterItem));
        }
        if (standHas) {
            return Stream.of(new ContainerWithdrawRecord(
                    support.newId(), 1, "entity-withdraw",
                    occurred, support.expiresAt(occurred),
                    support.playerOrigin(), support.playerSource(event.getPlayer()),
                    location, armorStandItem.getType().name(),
                    containerType, slot,
                    armorStandItem.getAmount(), beforeItem, null));
        }
        return Stream.of(new ContainerDepositRecord(
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
