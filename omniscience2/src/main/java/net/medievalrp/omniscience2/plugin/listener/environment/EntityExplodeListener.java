package net.medievalrp.omniscience2.plugin.listener.environment;

import java.time.Instant;
import java.util.Set;
import net.medievalrp.omniscience2.api.event.BlockBreakRecord;
import net.medievalrp.omniscience2.api.event.BlockSnapshot;
import net.medievalrp.omniscience2.api.event.ItemDropRecord;
import net.medievalrp.omniscience2.api.event.Origin;
import net.medievalrp.omniscience2.api.event.RecordContext;
import net.medievalrp.omniscience2.api.event.Source;
import net.medievalrp.omniscience2.api.event.StoredItem;
import net.medievalrp.omniscience2.api.util.BlockLocation;
import net.medievalrp.omniscience2.plugin.listener.RecordingSupport;
import net.medievalrp.omniscience2.plugin.listener.RecordingListener;
import net.medievalrp.omniscience2.plugin.pipeline.Recorder;
import net.medievalrp.omniscience2.plugin.util.BlockDependents;
import net.medievalrp.omniscience2.plugin.util.BlockLocations;
import net.medievalrp.omniscience2.plugin.util.BlockSnapshots;
import net.medievalrp.omniscience2.plugin.util.ContainerContents;
import net.medievalrp.omniscience2.plugin.util.ItemSerialization;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class EntityExplodeListener implements RecordingListener {

    private final Recorder recorder;
    private final RecordingSupport support;

    public EntityExplodeListener(Recorder recorder, RecordingSupport support) {
        this.recorder = recorder;
        this.support = support;
    }

    @Override
    public Set<String> events() {
        return Set.of("break", "drop");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        Entity entity = event.getEntity();
        String entityType = entity.getType().getKey().getKey();
        Instant occurred = support.now();
        Origin origin = support.environmentOrigin("entity-explode:" + entityType);
        Source source = support.entitySource(entity.getUniqueId(), entityType);

        for (Block block : event.blockList()) {
            emitBreak(block, occurred, origin, source);
            for (ItemStack stack : ContainerContents.stacksOf(block.getState())) {
                emitDrop(block, stack, occurred, origin, source);
            }
        }
        for (Block dependent : BlockDependents.collectDependentsBeyond(event.blockList())) {
            emitBreak(dependent, occurred, origin, source);
        }
    }

    private void emitBreak(Block block, Instant occurred, Origin origin, Source source) {
        BlockSnapshot original = BlockSnapshots.capture(block.getState());
        BlockLocation location = BlockLocations.fromLocation(block.getLocation());
        RecordContext ctx = support.context(occurred, origin, source, location);
        recorder.record(BlockBreakRecord.of(
                ctx, "break", original.material().name(), original, BlockSnapshots.air()));
    }

    private void emitDrop(Block container, ItemStack stack,
                          Instant occurred, Origin origin, Source source) {
        BlockLocation location = BlockLocations.fromLocation(container.getLocation());
        StoredItem stored = ItemSerialization.storedItem(0, stack);
        RecordContext ctx = support.context(occurred, origin, source, location);
        recorder.record(ItemDropRecord.of(ctx, stack.getType().name(), stack.getAmount(), stored));
    }
}
