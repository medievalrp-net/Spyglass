package net.medievalrp.spyglass.plugin.listener.block;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.medievalrp.spyglass.api.event.BlockBreakRecord;
import net.medievalrp.spyglass.api.event.BlockPlaceRecord;
import net.medievalrp.spyglass.api.event.EventRecord;
import net.medievalrp.spyglass.api.util.Duration;
import net.medievalrp.spyglass.plugin.listener.RecordingSupport;
import net.medievalrp.spyglass.plugin.pipeline.Recorder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/**
 * Bucket fluid logging (#228). Pins the invariants that matter:
 *
 * <ul>
 *   <li>Emptying a bucket records {@code bucket-empty} as a
 *       {@link BlockPlaceRecord} at the fluid block with the fluid material and
 *       the player; filling one records {@code bucket-fill} as a
 *       {@link BlockBreakRecord} with the picked-up fluid material.</li>
 *   <li>The rollback direction is right: an empty carries the pre-pour state as
 *       {@code originalBlock} (what a rollback restores when it removes the
 *       fluid) and the fluid as {@code newBlock}; a fill carries the fluid as
 *       {@code originalBlock} (what a rollback restores) and air as
 *       {@code newBlock}.</li>
 *   <li>Fish / lava variants map to the right material; powder-snow (a
 *       BlockItem, logged as {@code place}) and milk log nothing here.</li>
 *   <li>getAsString() is deferred to the serializer, never called inline
 *       (#154), and occurred + id are stamped at event time (#98).</li>
 *   <li>Each of the two event names is independently gated.</li>
 * </ul>
 */
class BucketListenerTest {

    private static final UUID PLAYER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID WORLD_ID  = UUID.fromString("77777777-7777-7777-7777-777777777777");

    private final CapturingRecorder recorder = new CapturingRecorder();
    private final RecordingSupport support   = new RecordingSupport(Duration.parse("4w"), "test");
    // Strong ref so Bukkit's weak World reference can't be collected mid-test.
    private final World world = mock(World.class);

    @Test
    void emptyingAWaterBucketRecordsBucketEmptyPlaceOffThread() {
        List<Runnable> deferred = new ArrayList<>();
        BucketListener listener = listener(deferred, enabled("bucket-empty", "bucket-fill"));
        Block block = block(Material.AIR, "minecraft:air");
        BlockData fluidData = fluidBlockData("minecraft:water[level=0]");

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.createBlockData(Material.WATER)).thenReturn(fluidData);

            listener.onBucketEmpty(emptyEvent(Material.WATER_BUCKET, block));

            // Nothing recorded inline: one deferred task, no records, no getAsString.
            assertThat(deferred).hasSize(1);
            assertThat(recorder.records).isEmpty();
            verify(block.getBlockData(), never()).getAsString();
            verify(fluidData, never()).getAsString();

            deferred.get(0).run();
        }

        assertThat(recorder.records).hasSize(1);
        BlockPlaceRecord record = (BlockPlaceRecord) recorder.records.get(0);
        assertThat(record.event()).isEqualTo("bucket-empty");
        assertThat(record.target()).isEqualTo("WATER");
        assertThat(record.source().playerName()).isEqualTo("Alice");
        assertThat(record.location().x()).isEqualTo(10);
        assertThat(record.location().y()).isEqualTo(64);
        assertThat(record.location().z()).isEqualTo(20);
        // Rollback removes the poured fluid: originalBlock (restored on rollback)
        // is the pre-pour air; newBlock is the water source.
        assertThat(record.originalBlock().material()).isEqualTo(Material.AIR);
        assertThat(record.newBlock().material()).isEqualTo(Material.WATER);
        assertThat(record.newBlock().blockData()).isEqualTo("minecraft:water[level=0]");
    }

    @Test
    void fillingABucketRecordsBucketFillBreakWithTheFluidMaterial() {
        List<Runnable> deferred = new ArrayList<>();
        BucketListener listener = listener(deferred, enabled("bucket-empty", "bucket-fill"));
        // Pre-pickup the block is still the lava source we scoop up.
        Block block = block(Material.LAVA, "minecraft:lava[level=0]");

        listener.onBucketFill(fillEvent(block));

        assertThat(deferred).hasSize(1);
        assertThat(recorder.records).isEmpty();
        verify(block.getBlockData(), never()).getAsString();

        deferred.get(0).run();

        assertThat(recorder.records).hasSize(1);
        BlockBreakRecord record = (BlockBreakRecord) recorder.records.get(0);
        assertThat(record.event()).isEqualTo("bucket-fill");
        // Material comes from the scooped block, not the bucket.
        assertThat(record.target()).isEqualTo("LAVA");
        assertThat(record.source().playerName()).isEqualTo("Alice");
        // Rollback restores the scooped fluid: originalBlock is the lava source,
        // newBlock is air.
        assertThat(record.originalBlock().material()).isEqualTo(Material.LAVA);
        assertThat(record.originalBlock().blockData()).isEqualTo("minecraft:lava[level=0]");
        assertThat(record.newBlock().isAir()).isTrue();
    }

    @Test
    void lavaBucketEmptyRecordsLavaSource() {
        assertThat(emptyTargetFor(Material.LAVA_BUCKET, Material.LAVA, "minecraft:lava[level=0]"))
                .isEqualTo("LAVA");
    }

    @Test
    void powderSnowBucketIsNotLoggedHere() {
        // Powder snow is a SolidBucketItem (a BlockItem): emptying it fires
        // BlockPlaceEvent - logged as `place` by BlockPlaceListener - not
        // PlayerBucketEmptyEvent. This listener must ignore it. (In practice the
        // event never even fires for it; this pins the fluidOf guard regardless.)
        List<Runnable> deferred = new ArrayList<>();
        BucketListener listener = listener(deferred, enabled("bucket-empty", "bucket-fill"));

        listener.onBucketEmpty(emptyEvent(Material.POWDER_SNOW_BUCKET, block(Material.AIR, "minecraft:air")));

        assertThat(deferred).isEmpty();
        assertThat(recorder.records).isEmpty();
    }

    @Test
    void fishBucketEmptiesWaterAndLogsTheWaterSource() {
        // A cod bucket places water (and spawns a cod, which is out of scope) -
        // so it must log a WATER bucket-empty, not the bucket item.
        assertThat(emptyTargetFor(Material.COD_BUCKET, Material.WATER, "minecraft:water[level=0]"))
                .isEqualTo("WATER");
    }

    @Test
    void milkBucketRecordsNothing() {
        List<Runnable> deferred = new ArrayList<>();
        BucketListener listener = listener(deferred, enabled("bucket-empty", "bucket-fill"));

        // Milk is drunk, not emptied into the world; even if the event fired it
        // places no block. No createBlockData call, so no Bukkit stub needed.
        listener.onBucketEmpty(emptyEvent(Material.MILK_BUCKET, block(Material.AIR, "minecraft:air")));

        assertThat(deferred).isEmpty();
        assertThat(recorder.records).isEmpty();
    }

    @Test
    void getAsStringIsDeferredAndOccurredStampedAtEventTime() {
        List<Runnable> deferred = new ArrayList<>();
        BucketListener listener = listener(deferred, enabled("bucket-empty", "bucket-fill"));
        Block block = block(Material.AIR, "minecraft:air");
        BlockData fluidData = fluidBlockData("minecraft:water[level=0]");

        Instant before = Instant.now();
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.createBlockData(Material.WATER)).thenReturn(fluidData);
            listener.onBucketEmpty(emptyEvent(Material.WATER_BUCKET, block));

            // Neither blockdata string built on the handling thread (#154).
            verify(block.getBlockData(), never()).getAsString();
            verify(fluidData, never()).getAsString();

            deferred.get(0).run();

            // Both built exactly once on the serializer thread.
            verify(block.getBlockData()).getAsString();
            verify(fluidData).getAsString();
        }
        Instant after = Instant.now();

        BlockPlaceRecord record = (BlockPlaceRecord) recorder.records.get(0);
        assertThat(record.occurred())
                .as("occurred must be stamped at event time, not serialization time")
                .isBetween(before, after);
        assertThat(record.id()).isNotNull();
    }

    @Test
    void bucketEmptyToggleGatesTheEmptyHandlerOnly() {
        List<Runnable> deferred = new ArrayList<>();
        // Only fills enabled: an empty must record nothing (and not even touch
        // Bukkit.createBlockData), while a fill still records.
        BucketListener listener = listener(deferred, enabled("bucket-fill"));

        listener.onBucketEmpty(emptyEvent(Material.WATER_BUCKET, block(Material.AIR, "minecraft:air")));
        assertThat(deferred).isEmpty();
        assertThat(recorder.records).isEmpty();

        listener.onBucketFill(fillEvent(block(Material.WATER, "minecraft:water[level=0]")));
        deferred.get(0).run();
        assertThat(recorder.records).hasSize(1);
        assertThat(recorder.records.get(0).event()).isEqualTo("bucket-fill");
    }

    @Test
    void bucketFillToggleGatesTheFillHandlerOnly() {
        List<Runnable> deferred = new ArrayList<>();
        BucketListener listener = listener(deferred, enabled("bucket-empty"));

        listener.onBucketFill(fillEvent(block(Material.WATER, "minecraft:water[level=0]")));
        assertThat(deferred).isEmpty();
        assertThat(recorder.records).isEmpty();
    }

    @Test
    void bothDisabledDoesNothingAndDefersNothing() {
        List<Runnable> deferred = new ArrayList<>();
        BucketListener listener = listener(deferred, enabled());

        listener.onBucketEmpty(emptyEvent(Material.WATER_BUCKET, block(Material.AIR, "minecraft:air")));
        listener.onBucketFill(fillEvent(block(Material.WATER, "minecraft:water[level=0]")));

        assertThat(deferred).isEmpty();
        assertThat(recorder.records).isEmpty();
    }

    // ── fixtures ─────────────────────────────────────────────────

    /** Empties {@code bucket} over an air block and returns the resulting target material. */
    private String emptyTargetFor(Material bucket, Material fluid, String fluidDataString) {
        List<Runnable> deferred = new ArrayList<>();
        BucketListener listener = listener(deferred, enabled("bucket-empty", "bucket-fill"));
        BlockData fluidData = fluidBlockData(fluidDataString);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.createBlockData(fluid)).thenReturn(fluidData);
            listener.onBucketEmpty(emptyEvent(bucket, block(Material.AIR, "minecraft:air")));
            deferred.get(0).run();
        }
        EventRecord record = recorder.records.get(recorder.records.size() - 1);
        assertThat(record.event()).isEqualTo("bucket-empty");
        return ((BlockPlaceRecord) record).target();
    }

    private BucketListener listener(List<Runnable> deferred, Set<String> enabled) {
        return new BucketListener(recorder, support, deferred::add, enabled);
    }

    private static Set<String> enabled(String... names) {
        return new HashSet<>(List.of(names));
    }

    private PlayerBucketEmptyEvent emptyEvent(Material bucket, Block block) {
        // Build the player before opening any stubbing on the event: nesting a
        // when() (player()) inside .thenReturn() trips UnfinishedStubbingException.
        Player player = player();
        PlayerBucketEmptyEvent event = mock(PlayerBucketEmptyEvent.class);
        when(event.getBucket()).thenReturn(bucket);
        when(event.getBlock()).thenReturn(block);
        when(event.getPlayer()).thenReturn(player);
        return event;
    }

    private PlayerBucketFillEvent fillEvent(Block block) {
        Player player = player();
        PlayerBucketFillEvent event = mock(PlayerBucketFillEvent.class);
        when(event.getBlock()).thenReturn(block);
        when(event.getPlayer()).thenReturn(player);
        return event;
    }

    private Player player() {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(PLAYER_ID);
        when(player.getName()).thenReturn("Alice");
        return player;
    }

    /**
     * A live block of {@code type}. Stubs both getBlockData() and
     * getState().getBlockData() to the same mock so the capture is deterministic
     * whether or not the static plainness cache is already warm for {@code type}
     * (BlockSnapshots.captureRawCached takes different paths cold vs warm).
     */
    private Block block(Material type, String blockDataString) {
        when(world.getUID()).thenReturn(WORLD_ID);
        when(world.getName()).thenReturn("world");
        BlockData data = mock(BlockData.class);
        when(data.getAsString()).thenReturn(blockDataString);
        when(data.getMaterial()).thenReturn(type);
        BlockState state = mock(BlockState.class);
        when(state.getType()).thenReturn(type);
        when(state.getBlockData()).thenReturn(data);
        Block block = mock(Block.class);
        when(block.getBlockData()).thenReturn(data);
        when(block.getState()).thenReturn(state);
        when(block.getWorld()).thenReturn(world);
        when(block.getX()).thenReturn(10);
        when(block.getY()).thenReturn(64);
        when(block.getZ()).thenReturn(20);
        when(block.getType()).thenReturn(type);
        return block;
    }

    private static BlockData fluidBlockData(String asString) {
        BlockData data = mock(BlockData.class);
        when(data.getAsString()).thenReturn(asString);
        return data;
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
