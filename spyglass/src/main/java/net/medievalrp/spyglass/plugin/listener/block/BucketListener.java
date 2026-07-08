package net.medievalrp.spyglass.plugin.listener.block;

import java.util.Set;
import java.util.concurrent.Executor;
import net.medievalrp.spyglass.api.event.BlockBreakRecord;
import net.medievalrp.spyglass.api.event.BlockPlaceRecord;
import net.medievalrp.spyglass.api.event.BlockSnapshot;
import net.medievalrp.spyglass.api.event.RecordContext;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.plugin.listener.RecordingListener;
import net.medievalrp.spyglass.plugin.listener.RecordingSupport;
import net.medievalrp.spyglass.plugin.pipeline.Recorder;
import net.medievalrp.spyglass.plugin.util.BlockLocations;
import net.medievalrp.spyglass.plugin.util.BlockSnapshots;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.jetbrains.annotations.ApiStatus;

/**
 * Logs fluid placement and pickup by a bucket - CoreProtect's
 * {@code PlayerBucketEmptyListener} / {@code PlayerBucketFillListener} parity
 * (#228). A bucket-placed water or lava source does NOT fire a
 * {@link org.bukkit.event.block.BlockPlaceEvent}, so without this listener a
 * poured moat or a lava-griefed build leaves no trace and cannot be rolled
 * back.
 *
 * <p>Scope is water and lava (including fish / axolotl / tadpole buckets, which
 * place a water source). A powder-snow bucket is deliberately out of scope: it
 * is a {@code SolidBucketItem} (a {@code BlockItem}), so emptying one fires a
 * normal {@code BlockPlaceEvent} and is already logged and rolled back as a
 * {@code place} by {@link BlockPlaceListener} - not a {@code PlayerBucketEmptyEvent}.
 *
 * <p>Two event names, both reusing an existing rollbackable record shape so
 * there is no new record type and no store/codec change (the #226 lever):
 * <ul>
 *   <li>{@code bucket-empty} -> {@link BlockPlaceRecord}: the fluid source
 *       block appeared, so a rollback removes it.</li>
 *   <li>{@code bucket-fill} -> {@link BlockBreakRecord}: the fluid source
 *       block was removed, so a rollback restores it.</li>
 * </ul>
 * Unlike the automated hopper flow (#226) these are player-driven, single
 * block, player-rate actions - exactly what rollback is for - so they are
 * rolled back and need no dedup.
 *
 * <p><b>Capture timing differs from {@link BlockPlaceListener}.</b> A
 * {@code BlockPlaceEvent} fires <em>after</em> the world changes, so that
 * listener reads {@code getBlock()} as the placed (after) state. A bucket event
 * fires <em>before</em> the world changes and is cancellable, so at
 * {@link EventPriority#MONITOR} the block is still in its pre-change state:
 * <ul>
 *   <li>bucket-empty: {@code getBlock()} is the soon-to-be-fluid block, still
 *       holding the old state - captured as the "before". The "after" fluid
 *       source is synthesized from the bucket material (matching {@code
 *       BlockIgniteListener}, which synthesizes the FIRE it places).</li>
 *   <li>bucket-fill: {@code getBlock()} is the fluid being picked up, still
 *       present - captured as the "before". The "after" is air.</li>
 * </ul>
 *
 * <p>As with the block listeners the live capture happens on the main thread
 * ({@link BlockSnapshots#captureRawCached}) and only the {@code getAsString()}
 * serialization is deferred to the injected serializer (#154). Sharing that
 * serializer keeps the rollback read-your-writes flush barrier (#98) intact, so
 * a rollback immediately after a pour still sees the record.
 */
@ApiStatus.Internal
public final class BucketListener implements RecordingListener {

    private final Recorder recorder;
    private final RecordingSupport support;
    private final Executor serializer;
    // Per-event gating: the plugin gates only at listener-registration
    // granularity (registers when any declared event is enabled), so the
    // independent bucket-empty / bucket-fill toggles are honoured here. Live,
    // thread-safe view of the enabled set.
    private final Set<String> enabledEvents;

    public BucketListener(Recorder recorder, RecordingSupport support, Executor serializer,
            Set<String> enabledEvents) {
        this.recorder = recorder;
        this.support = support;
        this.serializer = serializer;
        this.enabledEvents = enabledEvents;
    }

    @Override
    public Set<String> events() {
        return Set.of("bucket-empty", "bucket-fill");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (!enabledEvents.contains("bucket-empty")) {
            return;
        }
        Material fluid = fluidOf(event.getBucket());
        if (fluid == null) {
            // A milk bucket (drunk, not emptied into the world) or an
            // unrecognised bucket variant - nothing to place, so nothing to log.
            return;
        }
        Block block = event.getBlock();
        // Pre-change: the block still holds the state the pour replaces. This is
        // the "before" a rollback restores when it removes the poured fluid.
        BlockSnapshots.RawCapture rawBefore = BlockSnapshots.captureRawCached(block);
        // The placed fluid source. createBlockData() on the main thread mirrors
        // BlockIgniteListener; getAsString() stays deferred to the serializer.
        BlockData afterData = fluid.createBlockData();
        BlockLocation location = BlockLocations.fromBlock(block);
        // Stamp occurred + id at event time (#98) so an immediately-following
        // rollback flush sees a record dated to the pour, not to serialization.
        RecordContext ctx = support.playerContext(event.getPlayer(), location);
        String target = fluid.name();
        serializer.execute(() -> {
            BlockSnapshot before = BlockSnapshots.finishCapture(rawBefore);
            BlockSnapshot after = BlockSnapshots.of(fluid, afterData.getAsString());
            recorder.record(BlockPlaceRecord.of(ctx, "bucket-empty", target, before, after));
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (!enabledEvents.contains("bucket-fill")) {
            return;
        }
        Block block = event.getBlock();
        // Pre-change: the fluid source is still present, so the live capture IS
        // the "before" a rollback restores. The target material comes straight
        // from it (WATER / LAVA / POWDER_SNOW) rather than the bucket, which
        // avoids the empty-vs-filled ambiguity of getBucket() on a fill.
        BlockSnapshots.RawCapture rawBefore = BlockSnapshots.captureRawCached(block);
        BlockSnapshot after = BlockSnapshots.air();
        BlockLocation location = BlockLocations.fromBlock(block);
        RecordContext ctx = support.playerContext(event.getPlayer(), location);
        String target = rawBefore.type().name();
        serializer.execute(() -> {
            BlockSnapshot before = BlockSnapshots.finishCapture(rawBefore);
            recorder.record(BlockBreakRecord.of(ctx, "bucket-fill", target, before, after));
        });
    }

    /**
     * The world fluid a bucket empties, or {@code null} when this listener does
     * not log it. Fish / axolotl / tadpole buckets empty a water source (and
     * spawn an entity, which is out of scope here); lava maps to itself. Milk
     * (drunk, not emptied) and a powder-snow bucket (a block placed via
     * {@code BlockPlaceEvent}, logged as {@code place}) return null - the
     * {@code PlayerBucketEmptyEvent} they would need never fires.
     */
    private static Material fluidOf(Material bucket) {
        if (bucket == null) {
            return null;
        }
        return switch (bucket) {
            case LAVA_BUCKET -> Material.LAVA;
            case WATER_BUCKET, COD_BUCKET, SALMON_BUCKET, PUFFERFISH_BUCKET,
                    TROPICAL_FISH_BUCKET, AXOLOTL_BUCKET, TADPOLE_BUCKET -> Material.WATER;
            default -> null;
        };
    }
}
