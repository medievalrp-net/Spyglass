package net.medievalrp.spyglass.plugin.listener.modern;

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
import org.bukkit.block.Block;
import org.bukkit.block.DecoratedPot;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.DecoratedPotInventory;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

public final class DecoratedPotExtractor implements EventExtractor<PlayerInteractEvent, EventRecord> {

    private final ExtractorSupport support;

    public DecoratedPotExtractor(ExtractorSupport support) {
        this.support = support;
    }

    @Override
    public Class<PlayerInteractEvent> eventType() {
        return PlayerInteractEvent.class;
    }

    @Override
    public Stream<EventRecord> extract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return Stream.empty();
        }
        if (event.getHand() != EquipmentSlot.HAND) {
            return Stream.empty();
        }
        Block block = event.getClickedBlock();
        if (block == null || block.getType() != Material.DECORATED_POT) {
            return Stream.empty();
        }
        if (!(block.getState(false) instanceof DecoratedPot state)) {
            return Stream.empty();
        }
        DecoratedPotInventory inventory = state.getSnapshotInventory();
        ItemStack existing = inventory.getItem(0);
        ItemStack inHand = event.getPlayer().getInventory().getItemInMainHand();
        boolean hasExisting = existing != null && existing.getType() != Material.AIR;
        boolean hasInHand = inHand != null && inHand.getType() != Material.AIR;
        Player player = event.getPlayer();
        Instant occurred = support.now();
        BlockLocation location = BlockLocations.fromLocation(block.getLocation());

        if (player.isSneaking() && hasExisting) {
            StoredItem removed = ItemSerialization.storedItem(0, existing);
            return Stream.of(new ContainerWithdrawRecord(
                    support.newId(), 1, "pot-remove", occurred,
                    support.expiresAt(occurred),
                    support.playerOrigin(), support.playerSource(player),
                    location, existing.getType().name(), "DECORATED_POT",
                    0, existing.getAmount(), removed, null));
        }
        if (hasInHand && !hasExisting) {
            StoredItem inserted = ItemSerialization.storedItem(0, inHand);
            return Stream.of(new ContainerDepositRecord(
                    support.newId(), 1, "pot-insert", occurred,
                    support.expiresAt(occurred),
                    support.playerOrigin(), support.playerSource(player),
                    location, inHand.getType().name(), "DECORATED_POT",
                    0, 1, null, inserted));
        }
        return Stream.empty();
    }
}
