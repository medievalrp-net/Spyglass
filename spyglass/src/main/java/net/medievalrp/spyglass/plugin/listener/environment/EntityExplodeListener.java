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
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
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
    private final Executor serializer;

    public EntityExplodeListener(Recorder recorder, RecordingSupport support, Executor serializer) {
        this.recorder = recorder;
        this.support = support;
        this.serializer = serializer;
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
        Source source = explosionSource(entity, entityType);

        // Snapshot every break on the main thread (block state must be read
        // here) but DEFER the heavy item serialization off it. A single TNT
        // into a storage room would otherwise serialize every chest's contents
        // inline at MONITOR -- and twice, once into the rollback snapshot and
        // once per drop record -- spiking the tick (#129).
        List<PendingBreak> breaks = new ArrayList<>();
        for (Block block : event.blockList()) {
            BlockState state = block.getState();
            breaks.add(captureBreak(block, state, occurred, origin, source));
            for (ItemStack stack : ContainerContents.stacksOf(state)) {
                emitDrop(block, stack, occurred, origin, source);
            }
        }
        for (Block dependent : BlockDependents.collectDependentsBeyond(event.blockList())) {
            breaks.add(captureBreak(dependent, dependent.getState(), occurred, origin, source));
        }
        // Bed pairs, door halves, tall flowers, and cactus/sugar-cane/
        // kelp/bamboo stacks vanilla removes silently when the host is
        // destroyed - none of these surface as their own break events,
        // and they aren't dependents in the {@link BlockDependents}
        // taxonomy. v1 captured them via {@code saveMultiBreak}; this
        // restores parity for explosions.
        for (Block partner : MultiBlockPartners.partnersBeyond(event.blockList())) {
            breaks.add(captureBreak(partner, partner.getState(), occurred, origin, source));
        }
        recordBreaksDeferred(breaks);
    }

    // Player-lit TNT is the PLAYER's grief (#34): Paper tracks the
    // priming entity, so the source carries the igniter - one
    // p:<griefer> rollback covers the crater, which u:<player> can't do
    // on CoreProtect. The origin keeps the mechanism
    // ("entity-explode:tnt") either way. Chained / dispenser / redstone
    // TNT has no player source and stays entity-attributed, so c:tnt
    // sweeps still cover the unattributed remainder.
    Source explosionSource(Entity entity, String entityType) {
        if (entity instanceof org.bukkit.entity.TNTPrimed primed
                && primed.getSource() instanceof org.bukkit.entity.Player igniter) {
            return support.playerSource(igniter);
        }
        return support.entitySource(entity.getUniqueId(), entityType);
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

    // Off-thread: finish the cloned snapshots into BlockBreakRecords and bulk-
    // queue them. recordAll skips the per-record committed hook, which is
    // correct here: explosion breaks are a bulk block op like WorldEdit /
    // rollback, and firing one RecordCommittedEvent per exploded block (off the
    // main thread, no less) is exactly what the bulk path exists to avoid. The
    // shared DeferredSerializer is the recorder's flush barrier, so a rollback
    // right after a blast still drains these before it reads (#98).
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
