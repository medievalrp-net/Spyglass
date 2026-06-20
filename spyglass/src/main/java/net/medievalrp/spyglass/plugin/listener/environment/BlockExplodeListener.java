package net.medievalrp.spyglass.plugin.listener.environment;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import net.medievalrp.spyglass.api.event.BlockBreakRecord;
import net.medievalrp.spyglass.api.event.BlockSnapshot;
import net.medievalrp.spyglass.api.event.EventRecord;
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
import org.bukkit.block.BlockState;
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
    private final Executor serializer;

    public BlockExplodeListener(Recorder recorder, RecordingSupport support,
                                JavaPlugin plugin, Executor serializer) {
        this.recorder = recorder;
        this.support = support;
        this.plugin = plugin;
        this.serializer = serializer;
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

        // Snapshot every break on the main thread but DEFER the heavy item
        // serialization off it -- see EntityExplodeListener / #129. A blast
        // through storage would otherwise serialize every chest inline at
        // MONITOR (twice) and spike the tick.
        List<PendingBreak> breaks = new ArrayList<>();
        for (Block block : event.blockList()) {
            BlockState state = block.getState();
            breaks.add(captureBreak(block, state, occurred, origin, source));
            for (ItemStack stack : ContainerContents.stacksOf(state)) {
                emitDrop(block, stack, occurred, origin, source);
            }
        }
        // Cascade to attachments physics will silently remove (wall
        // torches on a wall outside the blast, pressure plates above a
        // destroyed floor). v1 walked these from onBlockExplode; v2 was
        // missing it until this pass.
        for (Block dependent : BlockDependents.collectDependentsBeyond(event.blockList())) {
            breaks.add(captureBreak(dependent, dependent.getState(), occurred, origin, source));
        }
        for (Block partner : MultiBlockPartners.partnersBeyond(event.blockList())) {
            breaks.add(captureBreak(partner, partner.getState(), occurred, origin, source));
        }
        recordBreaksDeferred(breaks);
    }

    // Main thread: read the block state and clone its contents into a cheap
    // RawCapture, stamp the time-ordered context. No serialization here.
    private PendingBreak captureBreak(Block block, BlockState state,
                                      Instant occurred, Origin origin, Source source) {
        BlockSnapshots.RawCapture raw = BlockSnapshots.captureRaw(state);
        BlockLocation location = BlockLocations.fromLocation(block.getLocation());
        RecordContext ctx = support.context(occurred, origin, source, location);
        return new PendingBreak(ctx, raw);
    }

    // Off-thread: finish the cloned snapshots and bulk-queue them. recordAll
    // skips the per-record committed hook (correct for a bulk block op; see
    // EntityExplodeListener), and the shared DeferredSerializer is the
    // recorder's flush barrier so a post-blast rollback still drains these (#98).
    private void recordBreaksDeferred(List<PendingBreak> breaks) {
        if (breaks.isEmpty()) {
            return;
        }
        serializer.execute(() -> {
            List<EventRecord> records = new ArrayList<>(breaks.size());
            for (PendingBreak pending : breaks) {
                BlockSnapshot original = BlockSnapshots.finishCapture(pending.raw());
                records.add(BlockBreakRecord.of(pending.ctx(), "break",
                        original.material().name(), original, BlockSnapshots.air()));
            }
            recorder.recordAll(records);
        });
    }

    private void emitDrop(Block container, ItemStack stack,
                          Instant occurred, Origin origin, Source source) {
        BlockLocation location = BlockLocations.fromLocation(container.getLocation());
        // Projection (no base64 blob): ItemDropRecord is forensic-only, never
        // rolled back or salvaged, so the blob is dead weight (#129 / #103).
        StoredItem stored = ItemSerialization.storedItemProjection(0, stack);
        RecordContext ctx = support.context(occurred, origin, source, location);
        recorder.record(ItemDropRecord.of(ctx, stack.getType().name(), stack.getAmount(), stored));
    }

    private record PendingBreak(RecordContext ctx, BlockSnapshots.RawCapture raw) {
    }
}
