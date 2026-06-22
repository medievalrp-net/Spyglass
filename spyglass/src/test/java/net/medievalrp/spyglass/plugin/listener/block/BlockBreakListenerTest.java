package net.medievalrp.spyglass.plugin.listener.block;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.medievalrp.spyglass.api.event.BlockBreakRecord;
import net.medievalrp.spyglass.api.event.EventRecord;
import net.medievalrp.spyglass.api.util.Duration;
import net.medievalrp.spyglass.plugin.listener.RecordingSupport;
import net.medievalrp.spyglass.plugin.pipeline.Recorder;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.junit.jupiter.api.Test;

/**
 * Pins the deferred getAsString() strategy for BlockBreakListener (#154):
 *
 * <ul>
 *   <li>captureRaw runs on the handling thread (main); getAsString() does NOT.</li>
 *   <li>finishCapture runs on the serializer thread; getAsString() runs there.</li>
 *   <li>The finished snapshot's blockData string is byte-identical to the value
 *       getAsString() returns - same as the old inline path.</li>
 *   <li>The RecordContext (occurred + id) is stamped at event time on the
 *       handling thread, not at serialization time (#98 read-your-writes).</li>
 *   <li>The after-snapshot uses the shared air() constant (no allocation).</li>
 * </ul>
 */
class BlockBreakListenerTest {

    private static final UUID PLAYER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID WORLD_ID  = UUID.fromString("77777777-7777-7777-7777-777777777777");

    private final CapturingRecorder recorder = new CapturingRecorder();
    private final RecordingSupport support   = new RecordingSupport(Duration.parse("4w"), "test");
    // Strong ref so Bukkit's weak World reference can't be collected mid-test.
    private final World world = mock(World.class);

    @Test
    void serializationIsDeferredToTheExecutorNotInline() {
        List<Runnable> deferred = new ArrayList<>();
        BlockBreakListener listener = new BlockBreakListener(recorder, support, deferred::add);

        listener.onBlockBreak(stoneBreakEvent(mock(BlockData.class)));

        // Nothing recorded inline - heavy build deferred.
        assertThat(deferred).hasSize(1);
        assertThat(recorder.records).isEmpty();

        deferred.get(0).run();

        assertThat(recorder.records).hasSize(1);
        BlockBreakRecord record = (BlockBreakRecord) recorder.records.get(0);
        assertThat(record.event()).isEqualTo("break");
        assertThat(record.target()).isEqualTo("STONE");
    }

    /**
     * #154: getAsString() must NOT be called on the handling thread (inside
     * captureRaw). It must only be called when the deferred task runs
     * (inside finishCapture).
     */
    @Test
    void getAsStringIsNotCalledOnHandlingThreadButIsCalledOnSerializerThread() {
        List<Runnable> deferred = new ArrayList<>();
        BlockBreakListener listener = new BlockBreakListener(recorder, support, deferred::add);
        BlockData blockData = mock(BlockData.class);
        when(blockData.getAsString()).thenReturn("minecraft:stone");

        listener.onBlockBreak(stoneBreakEvent(blockData));

        // Not yet - deferred to the serializer thread.
        verify(blockData, never()).getAsString();

        deferred.get(0).run();

        // Now it ran exactly once.
        verify(blockData).getAsString();
    }

    /**
     * #154: the blockData string in the finished record must be byte-identical
     * to the value getAsString() returns - same as the old inline path.
     */
    @Test
    void finishedRecordCarriesTheSameBlockDataStringAsGetAsString() {
        List<Runnable> deferred = new ArrayList<>();
        BlockBreakListener listener = new BlockBreakListener(recorder, support, deferred::add);
        BlockData blockData = mock(BlockData.class);
        when(blockData.getAsString()).thenReturn("minecraft:stone[waterlogged=false]");

        listener.onBlockBreak(stoneBreakEvent(blockData));
        deferred.get(0).run();

        BlockBreakRecord record = (BlockBreakRecord) recorder.records.get(0);
        assertThat(record.originalBlock().blockData())
                .isEqualTo("minecraft:stone[waterlogged=false]");
    }

    /**
     * #98 read-your-writes: occurred and id must be stamped at event time on
     * the handling thread, not at serialization time on the executor thread.
     * Even when the executor delays execution, the record's occurred timestamp
     * must fall inside the event-handling window.
     */
    @Test
    void occurredAndIdAreStampedAtEventTimeNotAtSerializationTime() {
        List<Runnable> deferred = new ArrayList<>();
        BlockBreakListener listener = new BlockBreakListener(recorder, support, deferred::add);

        Instant before = Instant.now();
        listener.onBlockBreak(stoneBreakEvent(mock(BlockData.class)));
        Instant after = Instant.now();

        // Simulate delayed executor (the record is built strictly after the event window).
        deferred.get(0).run();

        BlockBreakRecord record = (BlockBreakRecord) recorder.records.get(0);
        assertThat(record.occurred())
                .as("occurred must be stamped at event time, not serialization time")
                .isBetween(before, after);
        assertThat(record.id()).isNotNull();
    }

    /**
     * After-snapshot must be the shared air() constant - break always leaves air.
     */
    @Test
    void afterSnapshotIsTheSharedAirConstant() {
        List<Runnable> deferred = new ArrayList<>();
        BlockBreakListener listener = new BlockBreakListener(recorder, support, deferred::add);

        listener.onBlockBreak(stoneBreakEvent(mock(BlockData.class)));
        deferred.get(0).run();

        BlockBreakRecord record = (BlockBreakRecord) recorder.records.get(0);
        assertThat(record.newBlock().material()).isEqualTo(Material.AIR);
        assertThat(record.newBlock().blockData()).isEqualTo("minecraft:air");
        assertThat(record.newBlock().isAir()).isTrue();
    }

    // ── fixtures ────────────────────────────────────────────────

    /**
     * A mocked BlockBreakEvent for a STONE block with the given BlockData.
     * FallingBlockCascade reads the block's world/x/y/z to scan above -
     * we mock those minimally so the cascade finds no gravity-affected
     * blocks (world.getMaxHeight() returns 0 by default in mocks, which
     * exits the loop immediately).
     */
    private BlockBreakEvent stoneBreakEvent(BlockData blockData) {
        when(world.getUID()).thenReturn(WORLD_ID);
        when(world.getName()).thenReturn("world");
        // getMaxHeight() returns 0 by default from Mockito, so the cascade
        // loop exits immediately (y >= maxHeight).
        when(world.getMaxHeight()).thenReturn(0);

        BlockState state = mock(BlockState.class);
        when(state.getType()).thenReturn(Material.STONE);
        when(state.getBlockData()).thenReturn(blockData);

        Block block = mock(Block.class);
        when(block.getState()).thenReturn(state);
        when(block.getWorld()).thenReturn(world);
        when(block.getX()).thenReturn(10);
        when(block.getY()).thenReturn(64);
        when(block.getZ()).thenReturn(20);
        when(block.getType()).thenReturn(Material.STONE);

        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(PLAYER_ID);
        when(player.getName()).thenReturn("Alice");

        BlockBreakEvent event = mock(BlockBreakEvent.class);
        when(event.getBlock()).thenReturn(block);
        when(event.getPlayer()).thenReturn(player);
        return event;
    }

    private static final class CapturingRecorder implements Recorder {
        final List<EventRecord> records = new ArrayList<>();

        @Override
        public void record(EventRecord record) {
            records.add(record);
        }

        @Override
        public boolean flush(Duration timeout) {
            return true;
        }

        @Override
        public net.medievalrp.spyglass.plugin.pipeline.AsyncRecorder.ShutdownReport shutdown(Duration timeout) {
            return null;
        }
    }
}
