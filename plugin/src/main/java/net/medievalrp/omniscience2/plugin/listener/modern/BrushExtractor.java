package net.medievalrp.omniscience2.plugin.listener.modern;

import java.time.Instant;
import java.util.List;
import net.medievalrp.omniscience2.api.event.BlockBreakRecord;
import net.medievalrp.omniscience2.api.event.BlockSnapshot;
import net.medievalrp.omniscience2.api.util.BlockLocation;
import net.medievalrp.omniscience2.plugin.listener.ExtractorSupport;
import net.medievalrp.omniscience2.plugin.pipeline.Recorder;
import net.medievalrp.omniscience2.plugin.util.BlockLocations;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

public final class BrushExtractor implements Listener {

    private static final int DELAY_TICKS = 100;

    private final Recorder recorder;
    private final ExtractorSupport support;
    private final DelayedInteractionTracker tracker;

    public BrushExtractor(Recorder recorder, ExtractorSupport support, DelayedInteractionTracker tracker) {
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
        if (block == null) {
            return;
        }
        Material start = block.getType();
        if (start != Material.SUSPICIOUS_SAND && start != Material.SUSPICIOUS_GRAVEL) {
            return;
        }
        ItemStack inHand = event.getItem();
        if (inHand == null || inHand.getType() != Material.BRUSH) {
            return;
        }
        BlockLocation location = BlockLocations.fromLocation(block.getLocation());
        BlockSnapshot originalSnapshot = new BlockSnapshot(
                start, block.getBlockData().getAsString(),
                List.of(), List.of(), List.of(), List.of(), null);
        tracker.scheduleAfter(DELAY_TICKS, event.getPlayer(), block.getLocation(), ctx -> {
            if (ctx.currentMaterial() == start) {
                return;
            }
            Instant occurred = support.now();
            BlockSnapshot postSnapshot = new BlockSnapshot(
                    ctx.currentMaterial(),
                    ctx.location().getBlock().getBlockData().getAsString(),
                    List.of(), List.of(), List.of(), List.of(), null);
            recorder.record(new BlockBreakRecord(
                    support.newId(), 1, "brush", occurred,
                    support.expiresAt(occurred),
                    support.playerOrigin(),
                    support.playerSource(ctx.player()),
                    location, start.name(), originalSnapshot, postSnapshot));
        });
    }
}
