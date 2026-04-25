package net.medievalrp.omniscience2.plugin.listener.entity;

import java.util.Set;
import net.medievalrp.omniscience2.api.event.BlockBreakRecord;
import net.medievalrp.omniscience2.api.event.BlockSnapshot;
import net.medievalrp.omniscience2.api.event.Origin;
import net.medievalrp.omniscience2.api.event.RecordContext;
import net.medievalrp.omniscience2.api.event.Source;
import net.medievalrp.omniscience2.api.util.BlockLocation;
import net.medievalrp.omniscience2.plugin.listener.RecordingListener;
import net.medievalrp.omniscience2.plugin.listener.RecordingSupport;
import net.medievalrp.omniscience2.plugin.pipeline.Recorder;
import net.medievalrp.omniscience2.plugin.util.BlockLocations;
import net.medievalrp.omniscience2.plugin.util.BlockSnapshots;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Door;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityBreakDoorEvent;
import org.jetbrains.annotations.ApiStatus;

/**
 * Logs zombies (and other door-breaking mobs) destroying doors. Bukkit
 * fires {@code EntityBreakDoorEvent} instead of {@code BlockBreakEvent}
 * for this path, so without this listener the door vanishes from the
 * world without a record and rollback can't restore it.
 *
 * <p>Both halves of the door are emitted, attributed to the breaking
 * entity. Mirrors v1's {@code EventBreakListener.onEntityBreakDoor}.
 */
@ApiStatus.Internal
public final class EntityDoorBreakListener implements RecordingListener {

    private final Recorder recorder;
    private final RecordingSupport support;

    public EntityDoorBreakListener(Recorder recorder, RecordingSupport support) {
        this.recorder = recorder;
        this.support = support;
    }

    @Override
    public Set<String> events() {
        return Set.of("break");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityBreakDoor(EntityBreakDoorEvent event) {
        Entity entity = event.getEntity();
        String entityType = entity.getType().getKey().getKey();
        Origin origin = support.environmentOrigin("entity-break-door:" + entityType);
        Source source = support.entitySource(entity.getUniqueId(), entityType);

        emitBreak(event.getBlock(), origin, source);

        BlockData data = event.getBlockData();
        if (data instanceof Door door) {
            Block partner = door.getHalf() == Bisected.Half.TOP
                    ? event.getBlock().getRelative(BlockFace.DOWN)
                    : event.getBlock().getRelative(BlockFace.UP);
            if (partner.getBlockData() instanceof Door) {
                emitBreak(partner, origin, source);
            }
        }
    }

    private void emitBreak(Block block, Origin origin, Source source) {
        BlockSnapshot original = BlockSnapshots.capture(block.getState());
        BlockLocation location = BlockLocations.fromLocation(block.getLocation());
        RecordContext ctx = support.context(origin, source, location);
        recorder.record(BlockBreakRecord.of(
                ctx, "break", original.material().name(), original, BlockSnapshots.air()));
    }
}
