package net.medievalrp.spyglass.plugin.listener.block;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import net.medievalrp.spyglass.api.capture.BlockSnapshots;
import net.medievalrp.spyglass.api.event.BlockBreakRecord;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.plugin.listener.RecordingListener;
import net.medievalrp.spyglass.plugin.listener.RecordingSupport;
import net.medievalrp.spyglass.plugin.pipeline.Recorder;
import net.medievalrp.spyglass.plugin.util.BlockLocations;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Bed;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerBedLeaveEvent;
import org.bukkit.event.player.PlayerInteractEvent;

/** Records consumed straw beds, only after the captured cells actually disappear. */
public final class StrawBedListener implements RecordingListener {
    private final Recorder recorder;
    private final RecordingSupport support;
    private final Executor nextTick;
    private final Map<BlockLocation, Object> pending = new HashMap<>();

    public StrawBedListener(Recorder recorder, RecordingSupport support, Executor nextTick) {
        this.recorder = recorder;
        this.support = support;
        this.nextTick = nextTick;
    }

    @Override public Set<String> events() { return Set.of("straw-bed-consume"); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onUse(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.useInteractedBlock() == Event.Result.DENY) return;
        capturePair(event.getClickedBlock(), event.getPlayer(),
                () -> event.useInteractedBlock() == Event.Result.DENY);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLeave(PlayerBedLeaveEvent event) {
        if (!event.isCancelled()) capturePair(event.getBed(), event.getPlayer(), event::isCancelled);
    }

    // A manual break already has its own pair of break records.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!event.isCancelled()) forPair(event.getBlock(), b -> pending.remove(BlockLocations.fromBlock(b)));
    }

    private void capturePair(Block block, Player player, BooleanSupplier cancelled) {
        forPair(block, b -> capture(b, player, cancelled));
    }

    private static void forPair(Block block, java.util.function.Consumer<Block> consumer) {
        if (block == null || !block.getType().name().equals("STRAW_BED")) return;
        consumer.accept(block);
        if (block.getBlockData() instanceof Bed bed) {
            Block partner = block.getRelative(bed.getPart() == Bed.Part.HEAD
                    ? bed.getFacing().getOppositeFace() : bed.getFacing());
            if (partner.getType() == block.getType()) consumer.accept(partner);
        }
    }

    private void capture(Block block, Player player, BooleanSupplier cancelled) {
        var location = BlockLocations.fromBlock(block);
        if (pending.containsKey(location)) return;
        Object token = new Object();
        pending.put(location, token);
        var state = block.getState();
        if (state.getBlockData() instanceof Bed bed) {
            bed.setOccupied(false); // Restoring a consumed bed must not restore a phantom sleeper.
            state.setBlockData(bed);
        }
        var before = BlockSnapshots.capture(state);
        var context = support.playerContext(player, location);
        nextTick.execute(() -> {
            if (!pending.remove(location, token) || cancelled.getAsBoolean()
                    || !block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4)
                    || block.getType() != org.bukkit.Material.AIR) return;
            recorder.record(BlockBreakRecord.of(context, "straw-bed-consume", before.material().name(),
                    before, BlockSnapshots.air()));
        });
    }
}
