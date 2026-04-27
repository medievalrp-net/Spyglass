package net.medievalrp.spyglass.plugin.listener.environment;

import java.time.Instant;
import java.util.Set;
import net.medievalrp.spyglass.api.event.BlockBreakRecord;
import net.medievalrp.spyglass.api.event.BlockSnapshot;
import net.medievalrp.spyglass.api.event.ItemDropRecord;
import net.medievalrp.spyglass.api.event.Origin;
import net.medievalrp.spyglass.api.event.RecordContext;
import net.medievalrp.spyglass.api.event.Source;
import net.medievalrp.spyglass.api.event.StoredItem;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.plugin.listener.RecordingSupport;
import net.medievalrp.spyglass.plugin.listener.RecordingListener;
import net.medievalrp.spyglass.plugin.pipeline.Recorder;
import net.medievalrp.spyglass.plugin.util.BlockDependents;
import net.medievalrp.spyglass.plugin.util.BlockLocations;
import net.medievalrp.spyglass.plugin.util.BlockSnapshots;
import net.medievalrp.spyglass.plugin.util.ContainerContents;
import net.medievalrp.spyglass.plugin.util.ItemSerialization;
import net.medievalrp.spyglass.plugin.util.MultiBlockPartners;
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
        // Bed pairs, door halves, tall flowers, and cactus/sugar-cane/
        // kelp/bamboo stacks vanilla removes silently when the host is
        // destroyed — none of these surface as their own break events,
        // and they aren't dependents in the {@link BlockDependents}
        // taxonomy. v1 captured them via {@code saveMultiBreak}; this
        // restores parity for explosions.
        for (Block partner : MultiBlockPartners.partnersBeyond(event.blockList())) {
            emitBreak(partner, occurred, origin, source);
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
