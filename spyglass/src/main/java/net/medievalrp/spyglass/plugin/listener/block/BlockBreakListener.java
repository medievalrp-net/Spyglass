package net.medievalrp.spyglass.plugin.listener.block;

import java.util.Set;
import java.util.concurrent.Executor;
import net.medievalrp.spyglass.api.event.BlockBreakRecord;
import net.medievalrp.spyglass.api.event.BlockSnapshot;
import net.medievalrp.spyglass.api.event.RecordContext;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.plugin.listener.RecordingSupport;
import net.medievalrp.spyglass.plugin.listener.RecordingListener;
import net.medievalrp.spyglass.plugin.pipeline.Recorder;
import net.medievalrp.spyglass.plugin.util.BlockLocations;
import net.medievalrp.spyglass.plugin.util.BlockSnapshots;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.jetbrains.annotations.ApiStatus;

/**
 * Records block-break events. The BlockData string (the "minecraft:stone[...]"
 * representation) is the dominant per-event main-thread cost (~19% of
 * Spyglass's tick budget at 5000 ev/s per JFR). We defer it off the server
 * tick by using the captureRaw / finishCapture split (#154):
 *
 * <ul>
 *   <li>Main thread: call {@link BlockSnapshots#captureRaw} - reads live Bukkit
 *       state and carries an immutable {@code BlockData} copy. Build the
 *       {@link RecordContext} (stamps occurred + time-ordered id at event time).
 *   <li>Serializer thread: call {@link BlockSnapshots#finishCapture} - calls
 *       {@code getAsString()} on the detached {@code BlockData} and serializes
 *       any container contents, then hands the finished record to the recorder.
 * </ul>
 *
 * <p>Correctness: {@code BlockBreakRecord} is {@link net.medievalrp.spyglass.api.rollback.Rollbackable}.
 * The flush barrier ({@link net.medievalrp.spyglass.plugin.pipeline.DeferredSerializer#awaitQuiescence})
 * is wired as the recorder's pre-flush hook (#98), so a rollback read-your-writes
 * flush drains this stage before it drains the recorder queue - records broken
 * in a burst are visible to an immediately-following rollback.
 */
@ApiStatus.Internal
public final class BlockBreakListener implements RecordingListener {

    private final Recorder recorder;
    private final RecordingSupport support;
    private final Executor serializer;

    public BlockBreakListener(Recorder recorder, RecordingSupport support, Executor serializer) {
        this.recorder = recorder;
        this.support = support;
        this.serializer = serializer;
    }

    @Override
    public Set<String> events() {
        return Set.of("break");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        // captureRaw carries the immutable BlockData; getAsString() is deferred
        // to finishCapture on the serializer thread (#154).
        BlockSnapshots.RawCapture rawOriginal = BlockSnapshots.captureRaw(event.getBlock().getState());
        BlockSnapshot after = BlockSnapshots.air();
        BlockLocation location = BlockLocations.fromBlock(event.getBlock());
        // Build context (stamps occurred + time-ordered id) on the main thread
        // so the record reflects event time, not serialization time (#98).
        RecordContext ctx = support.playerContext(event.getPlayer(), location);
        String target = rawOriginal.type().name();
        serializer.execute(() -> {
            BlockSnapshot original = BlockSnapshots.finishCapture(rawOriginal);
            recorder.record(BlockBreakRecord.of(ctx, "break", target, original, after));
        });
        // Cascade: if breaking this block knocks out the support of a
        // gravity-affected column above (sand, gravel, anvil, concrete
        // powder, etc.), pre-emptively log the column's breaks tagged
        // to the same player. Without this, the column drops as
        // falling-block entities, the original cells aren't recorded
        // as "broken by player", and a /spyglass rollback p:them
        // restores the support but the sand stays gone.
        net.medievalrp.spyglass.plugin.util.FallingBlockCascade.emitCascadeAbove(
                recorder, support, event.getPlayer(),
                event.getBlock().getWorld(),
                event.getBlock().getX(), event.getBlock().getY(), event.getBlock().getZ());
    }
}
