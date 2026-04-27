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
        Action action = event.getAction();
        // Pressure plates fire PHYSICAL on player step. Ignore the
        // hand check for PHYSICAL — Bukkit reports that with no
        // EquipmentSlot. Skip the off-hand PHYSICAL spam from
        // RIGHT/LEFT click cases.
        if (action == Action.PHYSICAL) {
            // pass through
        } else if (action == Action.RIGHT_CLICK_BLOCK || action == Action.LEFT_CLICK_BLOCK) {
            if (event.getHand() != EquipmentSlot.HAND) {
                return;
            }
        } else {
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
        if (Tag.PRESSURE_PLATES.isTagged(type)) {
            return true;
        }
        // v1's {@code EventUseListener} matched the {@code Openable}
        // {@link org.bukkit.block.data.BlockData} interface, which covers
        // doors / trapdoors / fence gates uniformly. Tag.* gives us the
        // same coverage without per-material enumeration that breaks each
        // time a wood type is added.
        if (Tag.DOORS.isTagged(type)
                || Tag.TRAPDOORS.isTagged(type)
                || Tag.FENCE_GATES.isTagged(type)) {
            return true;
        }
        return switch (type) {
            case LEVER,
                 NOTE_BLOCK,
                 DAYLIGHT_DETECTOR,
                 REPEATER,
                 COMPARATOR,
                 SCULK_SENSOR,
                 CALIBRATED_SCULK_SENSOR,
                 // {@code CAKE} — v1 logged each bite. Useful to spot
                 // a player eating someone else's birthday cake.
                 CAKE -> true;
            default -> false;
        };
    }
}
