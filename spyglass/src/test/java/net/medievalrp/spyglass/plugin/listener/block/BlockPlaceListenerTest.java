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
import net.medievalrp.spyglass.api.event.BlockPlaceRecord;
import net.medievalrp.spyglass.api.event.EventRecord;
import net.medievalrp.spyglass.api.util.Duration;
import net.medievalrp.spyglass.plugin.listener.RecordingSupport;
import net.medievalrp.spyglass.plugin.pipeline.Recorder;
import net.medievalrp.spyglass.plugin.util.BlockSnapshots;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockPlaceEvent;
import org.junit.jupiter.api.Test;

/**
 * Pins the deferred getAsString() strategy for BlockPlaceListener (#154) and
 * the place-path allocation trims (#116):
 *
 * <ul>
 *   <li>getAsString() is NOT called on the handling thread for either capture
 *       (replaced or placed state); it is deferred to the serializer thread.</li>
 *   <li>When the replaced state is AIR the air() fast-path is taken, skipping
 *       captureRaw entirely for that snapshot (no getAsString() call at all).</li>
 *   <li>The finished record's blockData strings are byte-identical to the values
 *       getAsString() returns - same as the old inline path.</li>
 *   <li>occurred + id are stamped at event time (#98 read-your-writes).</li>
 * </ul>
 */
class BlockPlaceListenerTest {

    private static final UUID PLAYER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID WORLD_ID  = UUID.fromString("77777777-7777-7777-7777-777777777777");

    private final CapturingRecorder recorder = new CapturingRecorder();
    private final RecordingSupport support   = new RecordingSupport(Duration.parse("4w"), "test");
    // Strong ref so Bukkit's weak World reference can't be collected mid-test.
    private final World world = mock(World.class);

    @Test
    void serializationIsDeferredToTheExecutorNotInline() {
        List<Runnable> deferred = new ArrayList<>();
        BlockPlaceListener listener = new BlockPlaceListener(recorder, support, deferred::add);
        BlockData replacedData = airBlockData();
        BlockData placedData   = stoneBlockData();

        listener.onBlockPlace(placeEvent(Material.AIR, replacedData, Material.STONE, placedData));

        // Nothing recorded inline.
        assertThat(deferred).hasSize(1);
        assertThat(recorder.records).isEmpty();

        deferred.get(0).run();

        assertThat(recorder.records).hasSize(1);
        BlockPlaceRecord record = (BlockPlaceRecord) recorder.records.get(0);
        assertThat(record.event()).isEqualTo("place");
        assertThat(record.target()).isEqualTo("STONE");
    }

    /**
     * #154: getAsString() must NOT be called for either snapshot during
     * the handling (main) thread call. It runs only when the deferred task
     * executes.
     */
    @Test
    void getAsStringIsNotCalledOnHandlingThreadForPlacedState() {
        List<Runnable> deferred = new ArrayList<>();
        BlockPlaceListener listener = new BlockPlaceListener(recorder, support, deferred::add);
        BlockData replacedData = airBlockData();
        BlockData placedData   = stoneBlockData();

        listener.onBlockPlace(placeEvent(Material.AIR, replacedData, Material.STONE, placedData));

        // Neither call must have happened on the main (handling) thread.
        verify(placedData, never()).getAsString();
        // replacedData is AIR - captureRaw is skipped entirely via the fast-path.
        verify(replacedData, never()).getAsString();

        deferred.get(0).run();

        // After deferral: placed state's getAsString ran once.
        verify(placedData).getAsString();
        // Air fast-path: still zero calls on the replaced state's BlockData.
        verify(replacedData, never()).getAsString();
    }

    /**
     * When the replaced block is non-AIR, getAsString() for the replaced
     * state is also deferred (not called on the handling thread).
     */
    @Test
    void getAsStringForNonAirReplacedStateIsAlsoDeferred() {
        List<Runnable> deferred = new ArrayList<>();
        BlockPlaceListener listener = new BlockPlaceListener(recorder, support, deferred::add);
        BlockData replacedData = mock(BlockData.class);
        when(replacedData.getAsString()).thenReturn("minecraft:dirt");
        BlockData placedData = stoneBlockData();

        listener.onBlockPlace(placeEvent(Material.DIRT, replacedData, Material.STONE, placedData));

        // Neither called on main thread.
        verify(replacedData, never()).getAsString();
        verify(placedData, never()).getAsString();

        deferred.get(0).run();

        // Both called exactly once on the serializer thread.
        verify(replacedData).getAsString();
        verify(placedData).getAsString();

        BlockPlaceRecord record = (BlockPlaceRecord) recorder.records.get(0);
        assertThat(record.originalBlock().blockData()).isEqualTo("minecraft:dirt");
        assertThat(record.newBlock().blockData()).isEqualTo("minecraft:stone");
    }

    /**
     * #116 allocation trim: when the replaced state is AIR, the before-snapshot
     * must be the shared air() constant - not a newly captured RawCapture.
     * This skips the entire captureRaw path for the replaced state.
     */
    @Test
    void airReplacedStateUsesTheSharedAirConstant() {
        List<Runnable> deferred = new ArrayList<>();
        BlockPlaceListener listener = new BlockPlaceListener(recorder, support, deferred::add);

        listener.onBlockPlace(placeEvent(Material.AIR, airBlockData(), Material.STONE, stoneBlockData()));
        deferred.get(0).run();

        BlockPlaceRecord record = (BlockPlaceRecord) recorder.records.get(0);
        // The before-snapshot must be the air() constant: AIR material, "minecraft:air".
        assertThat(record.originalBlock().material()).isEqualTo(Material.AIR);
        assertThat(record.originalBlock().blockData()).isEqualTo("minecraft:air");
        assertThat(record.originalBlock().isAir()).isTrue();
        // Same instance as the shared constant.
        assertThat(record.originalBlock()).isSameAs(BlockSnapshots.air());
    }

    /**
     * #154 + #116: the after-snapshot's blockData string is byte-identical to
     * getAsString() for both AIR-replaced and non-AIR-replaced places.
     */
    @Test
    void finishedRecordCarriesTheSameBlockDataStringsAsGetAsString() {
        List<Runnable> deferred = new ArrayList<>();
        BlockPlaceListener listener = new BlockPlaceListener(recorder, support, deferred::add);
        BlockData placedData = mock(BlockData.class);
        when(placedData.getAsString()).thenReturn("minecraft:stone[waterlogged=false]");

        listener.onBlockPlace(placeEvent(Material.AIR, airBlockData(), Material.STONE, placedData));
        deferred.get(0).run();

        BlockPlaceRecord record = (BlockPlaceRecord) recorder.records.get(0);
        assertThat(record.newBlock().blockData()).isEqualTo("minecraft:stone[waterlogged=false]");
    }

    /**
     * #98 read-your-writes: occurred + id must be stamped at event time on the
     * handling thread, not at serialization time.
     */
    @Test
    void occurredAndIdAreStampedAtEventTimeNotAtSerializationTime() {
        List<Runnable> deferred = new ArrayList<>();
        BlockPlaceListener listener = new BlockPlaceListener(recorder, support, deferred::add);

        Instant before = Instant.now();
        listener.onBlockPlace(placeEvent(Material.AIR, airBlockData(), Material.STONE, stoneBlockData()));
        Instant after = Instant.now();

        // Simulate a delayed executor.
        deferred.get(0).run();

        BlockPlaceRecord record = (BlockPlaceRecord) recorder.records.get(0);
        assertThat(record.occurred())
                .as("occurred must be stamped at event time, not serialization time")
                .isBetween(before, after);
        assertThat(record.id()).isNotNull();
    }

    // ── fixtures ────────────────────────────────────────────────

    private BlockPlaceEvent placeEvent(Material replacedMaterial, BlockData replacedData,
                                       Material placedMaterial, BlockData placedData) {
        when(world.getUID()).thenReturn(WORLD_ID);
        when(world.getName()).thenReturn("world");

        // The replaced state (before).
        BlockState replacedState = mock(BlockState.class);
        when(replacedState.getType()).thenReturn(replacedMaterial);
        when(replacedState.getBlockData()).thenReturn(replacedData);

        // The placed state (after).
        BlockState placedState = mock(BlockState.class);
        when(placedState.getType()).thenReturn(placedMaterial);
        when(placedState.getBlockData()).thenReturn(placedData);

        // The block after placing (for fromBlock and getState()).
        Block block = mock(Block.class);
        when(block.getState()).thenReturn(placedState);
        when(block.getWorld()).thenReturn(world);
        when(block.getX()).thenReturn(5);
        when(block.getY()).thenReturn(64);
        when(block.getZ()).thenReturn(10);
        when(block.getType()).thenReturn(placedMaterial);

        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(PLAYER_ID);
        when(player.getName()).thenReturn("Alice");

        BlockPlaceEvent event = mock(BlockPlaceEvent.class);
        when(event.getBlock()).thenReturn(block);
        when(event.getBlockReplacedState()).thenReturn(replacedState);
        when(event.getPlayer()).thenReturn(player);
        return event;
    }

    private static BlockData airBlockData() {
        BlockData bd = mock(BlockData.class);
        when(bd.getAsString()).thenReturn("minecraft:air");
        return bd;
    }

    private static BlockData stoneBlockData() {
        BlockData bd = mock(BlockData.class);
        when(bd.getAsString()).thenReturn("minecraft:stone");
        return bd;
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
