package net.medievalrp.spyglass.plugin.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import net.medievalrp.spyglass.api.event.BlockSnapshot;
import net.medievalrp.spyglass.api.event.StoredItem;
import org.bukkit.Material;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.data.BlockData;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
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
 */
class BlockSnapshotsTest {

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
        assertThat(raw.blockData()).isEqualTo("minecraft:chest");
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

        return new Fixture(state, slot0, slot2, clone0, clone2);
    }

    private static ItemStack stack(Material material) {
        ItemStack stack = mock(ItemStack.class);
        when(stack.getType()).thenReturn(material);
        when(stack.hasItemMeta()).thenReturn(false);
        when(stack.serializeAsBytes()).thenReturn(new byte[]{(byte) material.ordinal()});
        return stack;
    }

    private record Fixture(BlockState state, ItemStack slot0, ItemStack slot2,
                           ItemStack clone0, ItemStack clone2) {
    }
}
