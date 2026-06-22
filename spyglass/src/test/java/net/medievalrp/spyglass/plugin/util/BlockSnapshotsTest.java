package net.medievalrp.spyglass.plugin.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import net.medievalrp.spyglass.api.event.BlockSnapshot;
import net.medievalrp.spyglass.api.event.StoredItem;
import org.bukkit.Material;
import org.bukkit.block.Banner;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.DecoratedPot;
import org.bukkit.block.Jukebox;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * #116: the "broken to air" after-snapshot is the same immutable value on
 * every break, so {@link BlockSnapshots#air()} returns a shared constant
 * instead of allocating per record. Sharing is safe because
 * {@link BlockSnapshot} is an immutable record and nothing identity-checks it.
 *
 * <p>#129: {@link BlockSnapshots#captureRaw} (main thread, clone only) /
 * {@link BlockSnapshots#finishCapture} (off thread, serialize) split lets the
 * explosion listeners keep the heavy item serialization off the tick. These
 * tests pin that the clone happens at capture time and the {@code
 * serializeAsBytes()} is deferred to finish time, and that {@code capture()}
 * (their composition) still produces the full inline snapshot.
 *
 * <p>#154: {@code captureRaw} now carries the immutable {@link BlockData}
 * object (not the string), and {@code finishCapture} calls
 * {@code getAsString()} off the main thread. Tests pin that
 * {@code getAsString()} is NOT called during {@code captureRaw} and IS called
 * during {@code finishCapture}, and that the resulting snapshot's
 * {@code blockData()} string is byte-identical to what the old inline path
 * would have produced.
 */
class BlockSnapshotsTest {

    @BeforeEach
    void clearPlainnessCache() {
        // The plainness cache (#168 stage 2) is static; reset it so each test
        // starts from a cold cache regardless of order.
        BlockSnapshots.resetPlainnessCache();
    }

    // ---- #168 stage 2: captureRawCached / the learned plainness cache --------

    /**
     * The first sighting of a material must consult the authoritative
     * {@link BlockState} (to learn its verdict); once proven plain, subsequent
     * captures of that material must skip {@code getState()} entirely.
     */
    @Test
    void captureRawCachedLearnsPlainThenSkipsGetState() {
        PlainFixture f = plainFixture(Material.STONE);

        BlockSnapshots.RawCapture r1 = BlockSnapshots.captureRawCached(f.block);
        // First time: must read the real state to learn the verdict.
        verify(f.block, times(1)).getState();

        BlockSnapshots.RawCapture r2 = BlockSnapshots.captureRawCached(f.block);
        // Proven plain: getState() is NOT called again - the whole point of #168.
        verify(f.block, times(1)).getState();

        // Both carry the immutable BlockData grab and no tile-entity payload.
        assertThat(r1.type()).isEqualTo(Material.STONE);
        assertThat(r2.type()).isEqualTo(Material.STONE);
        assertThat(r2.blockData()).isSameAs(f.data);
        assertThat(r2.containerContents()).isNull();
        assertThat(r2.signFront()).isEmpty();
        assertThat(r2.signBack()).isEmpty();
        assertThat(r2.bannerPatterns()).isEmpty();
        assertThat(r2.potSherds()).isEmpty();
        assertThat(r2.jukeboxRecord()).isNull();
    }

    /**
     * The plain fast path must finish to a snapshot identical to the one the
     * authoritative {@code captureRaw(getState())} path produces for a plain
     * block - same material, blockdata string, and (empty) payload.
     */
    @Test
    void plainFastPathFinishesIdenticallyToCaptureRaw() {
        PlainFixture f = plainFixture(Material.DIRT);

        BlockSnapshot viaState = BlockSnapshots.finishCapture(BlockSnapshots.captureRaw(f.state));

        BlockSnapshots.captureRawCached(f.block);                              // learn DIRT=plain
        BlockSnapshot viaFast = BlockSnapshots.finishCapture(
                BlockSnapshots.captureRawCached(f.block));                     // fast path

        assertThat(viaFast.material()).isEqualTo(viaState.material());
        assertThat(viaFast.blockData()).isEqualTo(viaState.blockData());
        assertThat(viaFast.containerItems()).isEqualTo(viaState.containerItems());
        assertThat(viaFast.simple()).isEqualTo(viaState.simple());
    }

    /**
     * SAFETY: a container must NEVER take the plain fast path. Every sighting
     * goes through {@code getState()} so its contents are captured - a misclassed
     * container would silently lose its contents and break rollback.
     */
    @Test
    void captureRawCachedNeverFastPathsAContainer() {
        Fixture cf = containerFixture();
        BlockData data = mock(BlockData.class);
        when(data.getMaterial()).thenReturn(Material.CHEST);
        Block block = mock(Block.class);
        when(block.getBlockData()).thenReturn(data);
        when(block.getState()).thenReturn(cf.state);

        BlockSnapshots.RawCapture r1 = BlockSnapshots.captureRawCached(block);
        verify(block, times(1)).getState();
        assertThat(r1.containerContents())
                .as("contents captured on the first (learning) sighting")
                .containsExactly(cf.clone0, null, cf.clone2);

        BlockSnapshots.RawCapture r2 = BlockSnapshots.captureRawCached(block);
        verify(block, times(2)).getState();
        assertThat(r2.containerContents())
                .as("a known-data-bearing material STILL goes through getState - no data loss")
                .containsExactly(cf.clone0, null, cf.clone2);
    }

    /**
     * {@code isDataBearing} is the safety predicate behind the cache: it must be
     * true for exactly the tile-entity types {@code captureRaw} special-cases,
     * and false for a plain state.
     */
    @Test
    void isDataBearingMatchesTheTileEntityTypesCaptureRawExtracts() {
        assertThat(BlockSnapshots.isDataBearing(mock(Container.class))).isTrue();
        assertThat(BlockSnapshots.isDataBearing(mock(Sign.class))).isTrue();
        assertThat(BlockSnapshots.isDataBearing(mock(Banner.class))).isTrue();
        assertThat(BlockSnapshots.isDataBearing(mock(Jukebox.class))).isTrue();
        assertThat(BlockSnapshots.isDataBearing(mock(DecoratedPot.class))).isTrue();
        assertThat(BlockSnapshots.isDataBearing(mock(BlockState.class)))
                .as("a plain block state is not data-bearing")
                .isFalse();
    }

    /** Verdicts are independent per material - learning one does not affect another. */
    @Test
    void verdictsAreLearnedPerMaterial() {
        PlainFixture stone = plainFixture(Material.STONE);
        BlockSnapshots.captureRawCached(stone.block);          // STONE -> plain
        BlockSnapshots.captureRawCached(stone.block);

        PlainFixture glass = plainFixture(Material.GLASS);
        BlockSnapshots.captureRawCached(glass.block);          // GLASS unseen -> consults state
        verify(glass.block, times(1)).getState();
    }

    @Test
    void airReturnsTheSameSharedInstance() {
        assertThat(BlockSnapshots.air())
                .as("air() must hand back one shared instance, not a fresh allocation")
                .isSameAs(BlockSnapshots.air());
    }

    @Test
    void airHasTheExpectedValue() {
        BlockSnapshot air = BlockSnapshots.air();
        assertThat(air.material()).isEqualTo(Material.AIR);
        assertThat(air.blockData()).isEqualTo("minecraft:air");
        assertThat(air.isAir()).isTrue();
        assertThat(air.simple())
                .as("an air snapshot carries no tile-entity payload")
                .isTrue();
        assertThat(air.containerItems()).isEmpty();
        assertThat(air.signFront()).isEmpty();
        assertThat(air.signBack()).isEmpty();
        assertThat(air.bannerPatterns()).isEmpty();
        assertThat(air.potSherds()).isEmpty();
        assertThat(air.jukeboxRecord()).isNull();
    }

    @Test
    void captureRawClonesContentsButDefersSerialization() {
        Fixture f = containerFixture();

        BlockSnapshots.RawCapture raw = BlockSnapshots.captureRaw(f.state);

        // Cloned on the main thread (detaches the stacks from the live world)...
        verify(f.slot0).clone();
        verify(f.slot2).clone();
        // ...but NOT serialized yet - that is the whole point of the split (#129).
        verify(f.clone0, never()).serializeAsBytes();
        verify(f.clone2, never()).serializeAsBytes();
        // Slots preserved, including the empty middle slot.
        assertThat(raw.containerContents()).containsExactly(f.clone0, null, f.clone2);
        assertThat(raw.type()).isEqualTo(Material.CHEST);
        // #154: blockData() now returns the BlockData object, not the string.
        // The string is deferred to finishCapture - verify the object is present.
        assertThat(raw.blockData()).isNotNull().isSameAs(f.blockData);
    }

    /**
     * #154: {@code getAsString()} must NOT be called during {@code captureRaw}
     * (that is the main-thread call we are deferring) and MUST be called during
     * {@code finishCapture} (the off-thread call).
     */
    @Test
    void getAsStringIsNotCalledOnCaptureRawButIsCalledOnFinishCapture() {
        Fixture f = containerFixture();

        BlockSnapshots.RawCapture raw = BlockSnapshots.captureRaw(f.state);
        // Not yet: getAsString() deferred to finishCapture (#154).
        verify(f.blockData, never()).getAsString();

        BlockSnapshots.finishCapture(raw);
        // Now it runs - exactly once.
        verify(f.blockData).getAsString();
    }

    /**
     * #154: the blockData string in the finished snapshot must be
     * byte-identical to the value {@code getAsString()} returns - same as the
     * old inline path that called it in {@code captureRaw}.
     */
    @Test
    void finishCaptureProducesTheSameBlockDataStringAsTheOldInlinePath() {
        Fixture f = containerFixture();
        // The mock returns "minecraft:chest" from getAsString().
        BlockSnapshot snapshot = BlockSnapshots.finishCapture(BlockSnapshots.captureRaw(f.state));
        assertThat(snapshot.blockData()).isEqualTo("minecraft:chest");
    }

    @Test
    void finishCaptureSerializesTheClonedContents() {
        Fixture f = containerFixture();
        BlockSnapshots.RawCapture raw = BlockSnapshots.captureRaw(f.state);

        BlockSnapshot snapshot = BlockSnapshots.finishCapture(raw);

        // The deferred serialization runs here, off the cloned stacks.
        verify(f.clone0).serializeAsBytes();
        verify(f.clone2).serializeAsBytes();
        assertThat(snapshot.material()).isEqualTo(Material.CHEST);
        assertThat(snapshot.containerItems())
                .extracting(StoredItem::slot)
                .containsExactly(0, 2);
        assertThat(snapshot.containerItems())
                .allSatisfy(item -> assertThat(item.data())
                        .as("finishCapture produces the base64 blob")
                        .isNotNull());
    }

    @Test
    void captureIsStillTheFullInlineSnapshot() {
        Fixture f = containerFixture();

        BlockSnapshot snapshot = BlockSnapshots.capture(f.state);

        assertThat(snapshot.material()).isEqualTo(Material.CHEST);
        assertThat(snapshot.blockData()).isEqualTo("minecraft:chest");
        assertThat(snapshot.containerItems())
                .extracting(StoredItem::material)
                .containsExactly(Material.DIAMOND.name(), Material.GOLD_INGOT.name());
    }

    /** A mocked CHEST {@link BlockState} holding two items (slots 0 and 2). */
    private static Fixture containerFixture() {
        ItemStack clone0 = stack(Material.DIAMOND);
        ItemStack clone2 = stack(Material.GOLD_INGOT);
        ItemStack slot0 = mock(ItemStack.class);
        ItemStack slot2 = mock(ItemStack.class);
        when(slot0.clone()).thenReturn(clone0);
        when(slot2.clone()).thenReturn(clone2);

        Inventory inventory = mock(Inventory.class);
        when(inventory.getContents()).thenReturn(new ItemStack[]{slot0, null, slot2});

        Container state = mock(Container.class);
        when(state.getSnapshotInventory()).thenReturn(inventory);
        when(state.getType()).thenReturn(Material.CHEST);
        BlockData blockData = mock(BlockData.class);
        when(blockData.getAsString()).thenReturn("minecraft:chest");
        when(state.getBlockData()).thenReturn(blockData);

        return new Fixture(state, slot0, slot2, clone0, clone2, blockData);
    }

    private static ItemStack stack(Material material) {
        ItemStack stack = mock(ItemStack.class);
        when(stack.getType()).thenReturn(material);
        when(stack.hasItemMeta()).thenReturn(false);
        when(stack.serializeAsBytes()).thenReturn(new byte[]{(byte) material.ordinal()});
        return stack;
    }

    private record Fixture(BlockState state, ItemStack slot0, ItemStack slot2,
                           ItemStack clone0, ItemStack clone2, BlockData blockData) {
    }

    /**
     * A live {@link Block} for a plain (non-tile-entity) material: its
     * {@code getBlockData()} grab and its {@code getState()} fallback both report
     * the same material and data, so {@code captureRawCached} works on either path.
     */
    private static PlainFixture plainFixture(Material material) {
        BlockData data = mock(BlockData.class);
        when(data.getMaterial()).thenReturn(material);
        when(data.getAsString()).thenReturn("minecraft:" + material.name().toLowerCase());

        BlockState state = mock(BlockState.class);   // plain - not any tile-entity type
        when(state.getType()).thenReturn(material);
        when(state.getBlockData()).thenReturn(data);

        Block block = mock(Block.class);
        when(block.getBlockData()).thenReturn(data);
        when(block.getState()).thenReturn(state);
        return new PlainFixture(block, state, data);
    }

    private record PlainFixture(Block block, BlockState state, BlockData data) {
    }
}
