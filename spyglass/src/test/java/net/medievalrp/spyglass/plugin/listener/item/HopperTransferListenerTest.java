package net.medievalrp.spyglass.plugin.listener.item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.medievalrp.spyglass.api.event.ContainerDepositRecord;
import net.medievalrp.spyglass.api.event.ContainerWithdrawRecord;
import net.medievalrp.spyglass.api.event.EventRecord;
import net.medievalrp.spyglass.api.util.Duration;
import net.medievalrp.spyglass.plugin.listener.RecordingSupport;
import net.medievalrp.spyglass.plugin.pipeline.Recorder;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Chest;
import org.bukkit.block.DoubleChest;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

/**
 * The slot-accurate hopper/dropper transfer listener. Pins the two-path
 * contract: a pure-destination endpoint snapshots at event time (contents
 * are still pre-write there) and diffs next tick; a source endpoint's
 * event-time contents are mid-split garbage on Paper, so its before-state
 * is reconstructed at drain time from the true contents plus the tick's
 * known moves reversed. Each changed slot emits exactly one
 * transfer-withdraw / transfer-deposit with the slot's (before, after)
 * pair - built off-main on the injected serializer.
 *
 * <p>InventoryType constants are never referenced: resolving one hits the
 * registry, which is not bootstrapped in a unit test. Mock inventories leave
 * {@code getType()} null and the listener falls back gracefully.
 */
class HopperTransferListenerTest {

    private static final UUID WORLD_ID = UUID.fromString("77777777-7777-7777-7777-777777777777");

    private final CapturingRecorder recorder = new CapturingRecorder();
    private final RecordingSupport support = new RecordingSupport(Duration.parse("4w"), "test");
    private final List<Runnable> serializer = new ArrayList<>();
    private final List<Runnable> nextTick = new ArrayList<>();
    // Strong ref so Location's weak World reference can't be collected mid-test.
    private final World world = mock(World.class);

    @Test
    void diffEmitsPerSlotPairsOnBothEndpoints() {
        Inventory source = inventory(64, 3);
        Inventory dest = inventory(63, 5);
        ItemStack five = mockStack(Material.COBBLESTONE, 5);
        when(source.getItem(1)).thenReturn(five);
        HopperTransferListener listener = listener(enabled("transfer-withdraw", "transfer-deposit"));

        listener.onInventoryMoveItem(move(source, dest, mockStack(Material.COBBLESTONE, 1)));

        // Nothing recorded inline; nothing even diffed yet.
        assertThat(recorder.records).isEmpty();
        assertThat(nextTick).hasSize(1);

        // The move applied: source slot 1 dropped to 4, dest slot 0 gained 1.
        ItemStack hoisted1 = mockStack(Material.COBBLESTONE, 4);
        when(source.getItem(1)).thenReturn(hoisted1);
        ItemStack hoisted2 = mockStack(Material.COBBLESTONE, 1);
        when(dest.getItem(0)).thenReturn(hoisted2);
        nextTick.forEach(Runnable::run);
        assertThat(recorder.records).as("records build on the serializer, not inline").isEmpty();
        serializer.forEach(Runnable::run);

        assertThat(recorder.records).hasSize(2);
        ContainerWithdrawRecord out = (ContainerWithdrawRecord) find("transfer-withdraw");
        ContainerDepositRecord in = (ContainerDepositRecord) find("transfer-deposit");
        assertThat(out.location().y()).isEqualTo(64);
        assertThat(out.slot()).isEqualTo(1);
        assertThat(out.amount()).isEqualTo(1);
        assertThat(out.beforeItem().material()).isEqualTo("COBBLESTONE");
        assertThat(out.afterItem()).as("partial stack remains").isNotNull();
        assertThat(in.location().y()).isEqualTo(63);
        assertThat(in.slot()).isEqualTo(0);
        assertThat(in.amount()).isEqualTo(1);
        assertThat(in.beforeItem()).as("slot was empty before").isNull();
        assertThat(in.afterItem().material()).isEqualTo("COBBLESTONE");
    }

    @Test
    void sameTickMovesShareOneSnapshotAndOneRecordPerSlot() {
        Inventory source = inventory(64, 3);
        Inventory dest = inventory(63, 5);
        ItemStack hoisted3 = mockStack(Material.COBBLESTONE, 5);
        when(source.getItem(0)).thenReturn(hoisted3);
        HopperTransferListener listener = listener(enabled("transfer-withdraw", "transfer-deposit"));

        // Two moves against the same endpoints in one tick.
        listener.onInventoryMoveItem(move(source, dest, mockStack(Material.COBBLESTONE, 1)));
        listener.onInventoryMoveItem(move(source, dest, mockStack(Material.COBBLESTONE, 1)));
        assertThat(nextTick).as("one drain per tick, not one per move").hasSize(1);

        ItemStack hoisted4 = mockStack(Material.COBBLESTONE, 3);
        when(source.getItem(0)).thenReturn(hoisted4);
        ItemStack hoisted5 = mockStack(Material.COBBLESTONE, 2);
        when(dest.getItem(0)).thenReturn(hoisted5);
        nextTick.forEach(Runnable::run);
        serializer.forEach(Runnable::run);

        assertThat(recorder.records).hasSize(2);
        assertThat(((ContainerWithdrawRecord) find("transfer-withdraw")).amount()).isEqualTo(2);
        assertThat(((ContainerDepositRecord) find("transfer-deposit")).amount()).isEqualTo(2);
    }

    @Test
    void honoursPerEventToggle() {
        Inventory source = inventory(64, 3);
        Inventory dest = inventory(63, 5);
        ItemStack hoisted6 = mockStack(Material.COBBLESTONE, 2);
        when(source.getItem(0)).thenReturn(hoisted6);
        HopperTransferListener listener = listener(enabled("transfer-deposit"));

        listener.onInventoryMoveItem(move(source, dest, mockStack(Material.COBBLESTONE, 1)));
        ItemStack hoisted7 = mockStack(Material.COBBLESTONE, 1);
        when(source.getItem(0)).thenReturn(hoisted7);
        ItemStack hoisted8 = mockStack(Material.COBBLESTONE, 1);
        when(dest.getItem(0)).thenReturn(hoisted8);
        nextTick.forEach(Runnable::run);
        serializer.forEach(Runnable::run);

        assertThat(recorder.records).hasSize(1);
        assertThat(recorder.records.get(0).event()).isEqualTo("transfer-deposit");
    }

    @Test
    void bothDisabledDoesNothingAndSchedulesNothing() {
        Inventory source = inventory(64, 3);
        Inventory dest = inventory(63, 5);
        HopperTransferListener listener = listener(enabled());

        listener.onInventoryMoveItem(move(source, dest, mockStack(Material.COBBLESTONE, 1)));

        assertThat(nextTick).isEmpty();
        assertThat(serializer).isEmpty();
        assertThat(recorder.records).isEmpty();
    }

    @Test
    void ignoresAirWithoutCloningOrScheduling() {
        ItemStack air = mock(ItemStack.class);
        when(air.getType()).thenReturn(Material.AIR);
        HopperTransferListener listener = listener(enabled("transfer-withdraw", "transfer-deposit"));

        listener.onInventoryMoveItem(move(inventory(64, 3), inventory(63, 5), air));

        assertThat(nextTick).isEmpty();
        assertThat(recorder.records).isEmpty();
        verify(air, never()).clone();
    }

    @Test
    void virtualSourceIsSkippedButRealDestinationStillRecords() {
        Inventory source = inventory(64, 3);
        when(source.getLocation()).thenReturn(null); // a virtual (blockless) inventory
        Inventory dest = inventory(63, 5);
        HopperTransferListener listener = listener(enabled("transfer-withdraw", "transfer-deposit"));

        listener.onInventoryMoveItem(move(source, dest, mockStack(Material.COBBLESTONE, 1)));
        ItemStack hoisted9 = mockStack(Material.COBBLESTONE, 1);
        when(dest.getItem(0)).thenReturn(hoisted9);
        nextTick.forEach(Runnable::run);
        serializer.forEach(Runnable::run);

        assertThat(recorder.records).hasSize(1);
        assertThat(recorder.records.get(0).event()).isEqualTo("transfer-deposit");
    }

    @Test
    void foreignMaterialChangesAreNotAttributedToTheTransfer() {
        Inventory source = inventory(64, 3);
        Inventory dest = inventory(63, 5);
        ItemStack hoisted10 = mockStack(Material.COBBLESTONE, 2);
        when(source.getItem(0)).thenReturn(hoisted10);
        HopperTransferListener listener = listener(enabled("transfer-withdraw", "transfer-deposit"));

        listener.onInventoryMoveItem(move(source, dest, mockStack(Material.COBBLESTONE, 1)));
        ItemStack hoisted11 = mockStack(Material.COBBLESTONE, 1);
        when(source.getItem(0)).thenReturn(hoisted11);
        ItemStack hoisted12 = mockStack(Material.COBBLESTONE, 1);
        when(dest.getItem(0)).thenReturn(hoisted12);
        // A player dropped gold into another dest slot the same tick.
        ItemStack hoisted13 = mockStack(Material.GOLD_INGOT, 7);
        when(dest.getItem(3)).thenReturn(hoisted13);
        nextTick.forEach(Runnable::run);
        serializer.forEach(Runnable::run);

        assertThat(recorder.records).hasSize(2);
        assertThat(recorder.records)
                .noneMatch(r -> "GOLD_INGOT".equals(targetOf(r)));
    }

    @Test
    void doubleChestSlotRebasesToTheOwningHalf() {
        // A hopper pulling from a double chest sees the combined 54-slot
        // inventory; raw slot 30 must attribute to the right half's block at
        // its local slot 3, the click listener's mapping.
        Inventory source = inventory(64, 54);
        DoubleChest holder = doubleChestHolder();
        when(source.getHolder()).thenReturn(holder);
        ItemStack hoisted14 = mockStack(Material.COBBLESTONE, 4);
        when(source.getItem(30)).thenReturn(hoisted14);
        Inventory dest = inventory(63, 5);
        HopperTransferListener listener = listener(enabled("transfer-withdraw", "transfer-deposit"));

        listener.onInventoryMoveItem(move(source, dest, mockStack(Material.COBBLESTONE, 1)));
        ItemStack hoisted15 = mockStack(Material.COBBLESTONE, 3);
        when(source.getItem(30)).thenReturn(hoisted15);
        ItemStack hoisted16 = mockStack(Material.COBBLESTONE, 1);
        when(dest.getItem(0)).thenReturn(hoisted16);
        nextTick.forEach(Runnable::run);
        serializer.forEach(Runnable::run);

        ContainerWithdrawRecord out = (ContainerWithdrawRecord) find("transfer-withdraw");
        assertThat(out.location().x()).as("right-half block").isEqualTo(2);
        assertThat(out.slot()).as("re-based local slot").isEqualTo(3);
    }

    // ── fixtures ─────────────────────────────────────────────────

    private HopperTransferListener listener(Set<String> enabled) {
        return new HopperTransferListener(recorder, support, serializer::add, nextTick::add, enabled);
    }

    private static Set<String> enabled(String... names) {
        return new HashSet<>(List.of(names));
    }

    private EventRecord find(String event) {
        return recorder.records.stream()
                .filter(r -> r.event().equals(event))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no record for event " + event));
    }

    private static String targetOf(EventRecord record) {
        if (record instanceof ContainerDepositRecord d) {
            return d.target();
        }
        if (record instanceof ContainerWithdrawRecord w) {
            return w.target();
        }
        return null;
    }

    /** An empty mock inventory at (10, y, 20), size slots, no holder. */
    private Inventory inventory(int y, int size) {
        when(world.getUID()).thenReturn(WORLD_ID);
        when(world.getName()).thenReturn("world");
        Inventory inv = mock(Inventory.class);
        when(inv.getLocation()).thenReturn(new Location(world, 10, y, 20));
        when(inv.getSize()).thenReturn(size);
        return inv;
    }

    private InventoryMoveItemEvent move(Inventory source, Inventory dest, ItemStack stack) {
        // getType() is left unstubbed (null) everywhere: referencing a real
        // InventoryType constant would hit the registry, which is not
        // bootstrapped in a unit test. The listener null-guards it.
        Inventory initiator = mock(Inventory.class);
        InventoryMoveItemEvent event = mock(InventoryMoveItemEvent.class);
        when(event.getItem()).thenReturn(stack);
        when(event.getSource()).thenReturn(source);
        when(event.getDestination()).thenReturn(dest);
        when(event.getInitiator()).thenReturn(initiator);
        return event;
    }

    private DoubleChest doubleChestHolder() {
        when(world.getUID()).thenReturn(WORLD_ID);
        when(world.getName()).thenReturn("world");
        Chest left = chestHalf(1);
        Chest right = chestHalf(2);
        DoubleChest doubleChest = mock(DoubleChest.class);
        when(doubleChest.getLeftSide()).thenReturn(left);
        when(doubleChest.getRightSide()).thenReturn(right);
        return doubleChest;
    }

    /** A 27-slot chest block at the given x, one half of a double chest. */
    private Chest chestHalf(int x) {
        org.bukkit.block.Block block = mock(org.bukkit.block.Block.class);
        when(block.getLocation()).thenReturn(new Location(world, x, 64, 2));
        when(block.getType()).thenReturn(Material.CHEST);
        Inventory blockInv = mock(Inventory.class);
        when(blockInv.getSize()).thenReturn(27);
        Chest chest = mock(Chest.class);
        when(chest.getBlock()).thenReturn(block);
        when(chest.getBlockInventory()).thenReturn(blockInv);
        return chest;
    }

    /** A mutable, self-cloning stack mock: the listener's drain-time
     *  reversal clones stacks and calls setAmount on them, so amounts must
     *  actually track. */
    private static ItemStack mockStack(Material material, int amount) {
        ItemStack stack = mock(ItemStack.class);
        int[] amt = {amount};
        when(stack.getType()).thenReturn(material);
        when(stack.getAmount()).thenAnswer(i -> amt[0]);
        org.mockito.Mockito.doAnswer(i -> {
            amt[0] = i.getArgument(0);
            return null;
        }).when(stack).setAmount(org.mockito.ArgumentMatchers.anyInt());
        when(stack.getMaxStackSize()).thenReturn(64);
        when(stack.getItemMeta()).thenReturn(null);
        when(stack.serializeAsBytes()).thenReturn(new byte[]{1, 2, 3});
        when(stack.clone()).thenAnswer(i -> mockStack(material, amt[0]));
        return stack;
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
