package net.medievalrp.spyglass.plugin.listener.modern;

import java.util.Set;
import net.medievalrp.spyglass.api.event.ContainerDepositRecord;
import net.medievalrp.spyglass.api.event.ContainerWithdrawRecord;
import net.medievalrp.spyglass.api.event.RecordContext;
import net.medievalrp.spyglass.api.event.StoredItem;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.plugin.listener.RecordingSupport;
import net.medievalrp.spyglass.plugin.listener.RecordingListener;
import net.medievalrp.spyglass.plugin.pipeline.Recorder;
import net.medievalrp.spyglass.plugin.util.BlockLocations;
import net.medievalrp.spyglass.plugin.util.ItemSerialization;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.DecoratedPot;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.DecoratedPotInventory;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class DecoratedPotListener implements RecordingListener {

    private final Recorder recorder;
    private final RecordingSupport support;

    public DecoratedPotListener(Recorder recorder, RecordingSupport support) {
        this.recorder = recorder;
        this.support = support;
    }

    @Override
    public Set<String> events() {
        return Set.of("pot-insert", "pot-remove");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null || block.getType() != Material.DECORATED_POT) {
            return;
        }
        if (!(block.getState(false) instanceof DecoratedPot state)) {
            return;
        }
        DecoratedPotInventory inventory = state.getSnapshotInventory();
        ItemStack existing = inventory.getItem(0);
        ItemStack inHand = event.getPlayer().getInventory().getItemInMainHand();
        boolean hasExisting = existing != null && existing.getType() != Material.AIR;
        boolean hasInHand = inHand != null && inHand.getType() != Material.AIR;
        Player player = event.getPlayer();
        BlockLocation location = BlockLocations.fromLocation(block.getLocation());

        if (player.isSneaking() && hasExisting) {
            StoredItem removed = ItemSerialization.storedItem(0, existing);
            RecordContext ctx = support.playerContext(player, location);
            recorder.record(ContainerWithdrawRecord.of(ctx, "pot-remove", existing.getType().name(),
                    "DECORATED_POT", 0, existing.getAmount(), removed, null));
            return;
        }
        if (hasInHand && !hasExisting) {
            StoredItem inserted = ItemSerialization.storedItem(0, inHand);
            RecordContext ctx = support.playerContext(player, location);
            recorder.record(ContainerDepositRecord.of(ctx, "pot-insert", inHand.getType().name(),
                    "DECORATED_POT", 0, 1, null, inserted));
        }
    }
}
