package net.medievalrp.spyglass.plugin.listener.player;

import java.util.Set;
import net.medievalrp.spyglass.api.event.BlockUseRecord;
import net.medievalrp.spyglass.api.event.RecordContext;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.plugin.listener.RecordingListener;
import net.medievalrp.spyglass.plugin.listener.RecordingSupport;
import net.medievalrp.spyglass.plugin.pipeline.Recorder;
import net.medievalrp.spyglass.plugin.util.BlockLocations;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.jetbrains.annotations.ApiStatus;

/**
 * Logs player right-clicks on redstone-interactive blocks that don't otherwise
 * produce a block-state or inventory change - levers, buttons, note blocks,
 * repeaters, comparators, daylight detectors, sculk sensors. Without this, a
 * griefer who flips a lever to open a piston trap leaves no footprint.
 */
@ApiStatus.Internal
public final class BlockUseListener implements RecordingListener {

    private final Recorder recorder;
    private final RecordingSupport support;

    public BlockUseListener(Recorder recorder, RecordingSupport support) {
        this.recorder = recorder;
        this.support = support;
    }

    @Override
    public Set<String> events() {
        return Set.of("use");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK
                && event.getAction() != Action.LEFT_CLICK_BLOCK) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }
        Material type = block.getType();
        if (!isUseBlock(type)) {
            return;
        }
        BlockLocation location = BlockLocations.fromLocation(block.getLocation());
        RecordContext ctx = support.playerContext(event.getPlayer(), location);
        recorder.record(BlockUseRecord.of(ctx, type.name()));
    }

    private static boolean isUseBlock(Material type) {
        if (Tag.BUTTONS.isTagged(type)) {
            return true;
        }
        return switch (type) {
            case LEVER,
                 NOTE_BLOCK,
                 DAYLIGHT_DETECTOR,
                 REPEATER,
                 COMPARATOR,
                 SCULK_SENSOR,
                 CALIBRATED_SCULK_SENSOR -> true;
            default -> false;
        };
    }
}
