package net.medievalrp.omniscience2.plugin.listener.modern;

import java.time.Instant;
import net.medievalrp.omniscience2.api.event.BlockBreakRecord;
import net.medievalrp.omniscience2.api.event.BlockSnapshot;
import net.medievalrp.omniscience2.api.util.BlockLocation;
import net.medievalrp.omniscience2.plugin.listener.ExtractorSupport;
import net.medievalrp.omniscience2.plugin.pipeline.Recorder;
import net.medievalrp.omniscience2.plugin.util.BlockLocations;
import net.medievalrp.omniscience2.plugin.util.BlockSnapshots;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

public final class VaultExtractor implements Listener {

    private static final int DELAY_TICKS = 2;

    private final Recorder recorder;
    private final ExtractorSupport support;
    private final DelayedInteractionTracker tracker;

    public VaultExtractor(Recorder recorder, ExtractorSupport support, DelayedInteractionTracker tracker) {
        this.recorder = recorder;
        this.support = support;
        this.tracker = tracker;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null || block.getType() != Material.VAULT) {
            return;
        }
        ItemStack inHand = event.getItem();
        if (inHand == null || inHand.getType() != Material.TRIAL_KEY && inHand.getType() != Material.OMINOUS_TRIAL_KEY) {
            return;
        }
        Material keyMaterial = inHand.getType();
        BlockLocation location = BlockLocations.fromLocation(block.getLocation());
        String blockData = block.getBlockData().getAsString();
        tracker.scheduleAfter(DELAY_TICKS, event.getPlayer(), block.getLocation(), ctx -> {
            Block now = ctx.location().getBlock();
            if (now.getType() != Material.VAULT) {
                return;
            }
            String nowData = now.getBlockData().getAsString();
            if (nowData.equals(blockData)) {
                return;
            }
            Instant occurred = support.now();
            BlockSnapshot pre = BlockSnapshots.of(Material.VAULT, blockData);
            BlockSnapshot post = BlockSnapshots.of(Material.VAULT, nowData);
            recorder.record(new BlockBreakRecord(
                    support.newId(), 1, "vault", occurred,
                    support.expiresAt(occurred),
                    support.playerOrigin(),
                    support.playerSource(ctx.player()),
                    location, keyMaterial.name(), pre, post));
        });
    }
}
