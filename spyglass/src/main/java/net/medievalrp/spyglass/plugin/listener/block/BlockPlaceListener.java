package net.medievalrp.spyglass.plugin.listener.block;

import java.util.Set;
import java.util.concurrent.Executor;
import net.medievalrp.spyglass.api.event.BlockPlaceRecord;
import net.medievalrp.spyglass.api.event.BlockSnapshot;
import net.medievalrp.spyglass.api.event.RecordContext;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.plugin.listener.RecordingSupport;
import net.medievalrp.spyglass.plugin.listener.RecordingListener;
import net.medievalrp.spyglass.plugin.pipeline.Recorder;
import net.medievalrp.spyglass.plugin.util.BlockLocations;
import net.medievalrp.spyglass.plugin.util.BlockSnapshots;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockPlaceEvent;
import org.jetbrains.annotations.ApiStatus;

/**
 * Records block-place events. Applies the same deferred getAsString() strategy
 * as {@link BlockBreakListener} (#154): captureRaw on the main thread carries
 * an immutable BlockData object, finishCapture on the serializer thread calls
 * getAsString() off-tick.
 *
 * <p>A place event captures twice: the replaced state (before) and the placed
 * state (after). Both getAsString() calls are deferred to the serializer thread.
 *
 * <p>Allocation trims vs the old path (#116):
 * <ul>
 *   <li>When the replaced state is AIR (placing into empty space - the common
 *       case), the shared {@link BlockSnapshots#air()} constant is used instead
 *       of a full captureRaw, skipping the BlockState read, BlockData copy, and
 *       eventual getAsString() call entirely.</li>
 *   <li>{@link BlockLocations#fromBlock} replaces the old
 *       {@code fromLocation(block.getLocation())} which allocated a throwaway
 *       {@code Location} object.</li>
 * </ul>
 *
 * <p>Correctness: {@link BlockPlaceRecord} is {@link net.medievalrp.spyglass.api.rollback.Rollbackable}.
 * The flush barrier ({@link net.medievalrp.spyglass.plugin.pipeline.DeferredSerializer#awaitQuiescence})
 * is wired as the recorder's pre-flush hook (#98), keeping read-your-writes safe.
 */
@ApiStatus.Internal
public final class BlockPlaceListener implements RecordingListener {

    private final Recorder recorder;
    private final RecordingSupport support;
    private final Executor serializer;

    public BlockPlaceListener(Recorder recorder, RecordingSupport support, Executor serializer) {
        this.recorder = recorder;
        this.support = support;
        this.serializer = serializer;
    }

    @Override
    public Set<String> events() {
        return Set.of("place");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        // Allocation trim (#116): skip a full captureRaw for the replaced state
        // when it is AIR - placing into empty space is the common path. The
        // shared air() constant is immutable and identity-safe.
        BlockSnapshots.RawCapture rawBefore = event.getBlockReplacedState().getType() == Material.AIR
                ? null
                : BlockSnapshots.captureRaw(event.getBlockReplacedState());

        // captureRawCached grabs only the immutable BlockData and skips the
        // CraftBlockState build for materials proven to carry no tile-entity data
        // (#168 stage 2); getAsString() stays deferred to finishCapture (#154).
        // (The before-state above uses the snapshot Bukkit already built for the
        // event, so there is no getState() of ours to skip there.)
        BlockSnapshots.RawCapture rawAfter = BlockSnapshots.captureRawCached(event.getBlock());

        // Allocation trim (#116): fromBlock avoids a throwaway Location allocation.
        BlockLocation location = BlockLocations.fromBlock(event.getBlock());

        // Build context (stamps occurred + time-ordered id) on the main thread
        // so the record reflects event time, not serialization time (#98).
        RecordContext ctx = support.playerContext(event.getPlayer(), location);
        String target = rawAfter.type().name();

        // rawBefore is either null (air fast-path) or a full RawCapture.
        // Capture the null-ness here for the lambda; the lambda resolves it
        // off-thread without touching the main thread.
        boolean beforeIsAir = rawBefore == null;
        serializer.execute(() -> {
            BlockSnapshot before = beforeIsAir
                    ? BlockSnapshots.air()
                    : BlockSnapshots.finishCapture(rawBefore);
            BlockSnapshot after = BlockSnapshots.finishCapture(rawAfter);
            recorder.record(BlockPlaceRecord.of(ctx, "place", target, before, after));
        });
    }
}
