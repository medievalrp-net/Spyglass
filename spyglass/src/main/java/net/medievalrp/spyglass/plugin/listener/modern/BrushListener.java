package net.medievalrp.spyglass.plugin.listener.modern;

import java.time.Instant;
import java.util.Set;
import net.medievalrp.spyglass.api.event.BlockBreakRecord;
import net.medievalrp.spyglass.api.event.BlockSnapshot;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.plugin.listener.RecordingSupport;
import net.medievalrp.spyglass.plugin.listener.RecordingListener;
import net.medievalrp.spyglass.plugin.pipeline.Recorder;
import net.medievalrp.spyglass.plugin.util.BlockLocations;
import net.medievalrp.spyglass.plugin.util.BlockSnapshots;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class BrushListener implements RecordingListener {

    private static final int DELAY_TICKS = 100;

    private final Recorder recorder;
    private final RecordingSupport support;
    private final DelayedInteractionTracker tracker;

    public BrushListener(Recorder recorder, RecordingSupport support, DelayedInteractionTracker tracker) {
        this.recorder = recorder;
        this.support = support;
        this.tracker = tracker;
    }

    @Override
    public Set<String> events() {
        return Set.of("brush");
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
        BlockSnapshot originalSnapshot = BlockSnapshots.of(start, block.getBlockData().getAsString());
        tracker.scheduleAfter(DELAY_TICKS, event.getPlayer(), block.getLocation(), ctx -> {
            if (ctx.currentMaterial() == start) {
                return;
            }
            Instant occurred = support.now();
            BlockSnapshot postSnapshot = BlockSnapshots.of(
                    ctx.currentMaterial(),
                    ctx.location().getBlock().getBlockData().getAsString());
            recorder.record(new BlockBreakRecord(
                    support.newId(), 1, "brush", occurred,
                    support.expiresAt(occurred),
                    support.playerOrigin(),
                    support.playerSource(ctx.player()),
                    location, start.name(), originalSnapshot, postSnapshot));
        });
    }
}
