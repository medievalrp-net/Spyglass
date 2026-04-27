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
import net.medievalrp.spyglass.plugin.util.PlayerSourceMetadata;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class BlockExplodeListener implements RecordingListener {

    private final Recorder recorder;
    private final RecordingSupport support;
    private final JavaPlugin plugin;

    public BlockExplodeListener(Recorder recorder, RecordingSupport support, JavaPlugin plugin) {
        this.recorder = recorder;
        this.support = support;
        this.plugin = plugin;
    }

    @Override
    public Set<String> events() {
        return Set.of("break", "drop");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        String cause = event.getBlock().getType().name();
        Instant occurred = support.now();
        Origin origin = support.environmentOrigin("block-explode:" + cause);

        // Player-lit TNT keeps its metadata through the priming stage.
        PlayerSourceMetadata.Attribution attribution =
                PlayerSourceMetadata.attributionOf(event.getBlock(), plugin);
        Source source = attribution.isPresent()
                ? Source.player(attribution.id(), attribution.name())
                : support.environmentSource("block-explode:" + cause);

        for (Block block : event.blockList()) {
            emitBreak(block, occurred, origin, source);
            for (ItemStack stack : ContainerContents.stacksOf(block.getState())) {
                emitDrop(block, stack, occurred, origin, source);
            }
        }
        // Cascade to attachments physics will silently remove (wall
        // torches on a wall outside the blast, pressure plates above a
        // destroyed floor). v1 walked these from onBlockExplode; v2 was
        // missing it until this pass.
        for (Block dependent : BlockDependents.collectDependentsBeyond(event.blockList())) {
            emitBreak(dependent, occurred, origin, source);
        }
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
