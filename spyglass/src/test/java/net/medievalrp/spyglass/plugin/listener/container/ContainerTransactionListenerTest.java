package net.medievalrp.spyglass.plugin.listener.container;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
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
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;

/**
 * Pins the (before, after) slot-state pair contract (#28): the rollback
 * effect for a transaction writes {@code before} back only while the
 * live slot still equals {@code after}, so a record whose {@code after}
 * doesn't reflect the post-click slot is permanently un-rollbackable.
 * The original code hardcoded {@code after = null} for deposits, which
 * made every deposit rollback skip with "slot changed".
 */
class ContainerTransactionListenerTest {

    private static final UUID PLAYER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private final CapturingRecorder recorder = new CapturingRecorder();
    private final RecordingSupport support = new RecordingSupport(Duration.parse("4w"), "test");
    // Queued next-tick executor: shift-click deposits diff the container
    // one tick after the click applies (#268), so tests re-stub the top
    // inventory to its post-click state and then drain this queue.
    private final List<Runnable> nextTick = new java.util.ArrayList<>();
    // Same-thread serializer so the deferred build (#98) runs inline and the
    // (before, after) assertions below see the records immediately. A
    // separate test exercises the actual deferral handoff.
    private final ContainerTransactionListener listener =
            new ContainerTransactionListener(recorder, support, Runnable::run, nextTick::add);

    // Strong refs so Location's weak World reference can't be collected
    // mid-test (see ChatListenerTest.mockPlayer).
    private final World world = mock(World.class);

    @Test
    void depositIntoEmptySlotRecordsComputedAfterState() {
        InventoryClickEvent event = clickEvent(InventoryAction.PLACE_ALL, 0,
                null, ironStack(64));

        listener.onInventoryClick(event);

        assertThat(recorder.records).hasSize(1);
        ContainerDepositRecord record = (ContainerDepositRecord) recorder.records.get(0);
        assertThat(record.amount()).isEqualTo(64);
        assertThat(record.beforeItem()).isNull();
        assertThat(record.afterItem()).isNotNull();
        assertThat(record.afterItem().material()).isEqualTo("IRON_INGOT");
    }

    @Test
    void partialWithdrawRecordsRemainderAsAfterState() {
        // PICKUP_HALF of a 16-stack: 8 leave, 8 remain.
        InventoryClickEvent event = clickEvent(InventoryAction.PICKUP_HALF, 0,
                ironStack(16), null);

        listener.onInventoryClick(event);

        assertThat(recorder.records).hasSize(1);
        ContainerWithdrawRecord record = (ContainerWithdrawRecord) recorder.records.get(0);
        assertThat(record.amount()).isEqualTo(8);
        assertThat(record.beforeItem()).isNotNull();
        assertThat(record.afterItem())
                .as("partial withdraw must record the remaining stack, not null")
                .isNotNull();
    }

    @Test
    void fullWithdrawRecordsEmptyAfterState() {
        InventoryClickEvent event = clickEvent(InventoryAction.PICKUP_ALL, 0,
                ironStack(16), null);

        listener.onInventoryClick(event);

        ContainerWithdrawRecord record = (ContainerWithdrawRecord) recorder.records.get(0);
        assertThat(record.amount()).isEqualTo(16);
        assertThat(record.afterItem()).isNull();
    }

    @Test
    void hotbarSwapRecordsOneSharedStatePair() {
        ItemStack slotItem = ironStack(5);
        ItemStack hotbarItem = mockStack(Material.GOLD_INGOT, 7);
        PlayerInventory playerInv = mock(PlayerInventory.class);
        when(playerInv.getItem(3)).thenReturn(hotbarItem);
        InventoryClickEvent event = clickEvent(InventoryAction.HOTBAR_SWAP, 0, slotItem, null);
        Player player = (Player) event.getWhoClicked();
        when(player.getInventory()).thenReturn(playerInv);
        when(event.getHotbarButton()).thenReturn(3);

        listener.onInventoryClick(event);

        assertThat(recorder.records).hasSize(2);
        ContainerWithdrawRecord withdraw = (ContainerWithdrawRecord) recorder.records.get(0);
        ContainerDepositRecord deposit = (ContainerDepositRecord) recorder.records.get(1);
        // Both records describe the same slot transition: iron out, gold in.
        assertThat(withdraw.beforeItem().material()).isEqualTo("IRON_INGOT");
        assertThat(withdraw.afterItem().material()).isEqualTo("GOLD_INGOT");
        assertThat(deposit.beforeItem().material()).isEqualTo("IRON_INGOT");
        assertThat(deposit.afterItem().material()).isEqualTo("GOLD_INGOT");
    }

    @Test
    void doubleChestDepositIsRecorded() {
        // A double chest's holder is a DoubleChest (not a Container), so the
        // old resolveTarget returned null and silently dropped every chest
        // deposit while barrels (a Container) kept working.
        InventoryClickEvent event = doubleChestClickEvent(InventoryAction.PLACE_ALL, 0,
                null, ironStack(64));

        listener.onInventoryClick(event);

        assertThat(recorder.records)
                .as("double-chest deposits must be recorded, not dropped")
                .hasSize(1);
        ContainerDepositRecord record = (ContainerDepositRecord) recorder.records.get(0);
        assertThat(record.amount()).isEqualTo(64);
        // Left-half slot 0 attributes to the left block at x=1, slot unchanged.
        assertThat(record.location().x()).isEqualTo(1);
        assertThat(record.slot()).isEqualTo(0);
    }

    @Test
    void doubleChestRightHalfSlotIsRebasedToOwningBlock() {
        // Raw slot 30 lives in the right half (slots 27-53). It must attribute
        // to the right block and re-base to that block's local slot 3, so the
        // single-block rollback path can find it.
        InventoryClickEvent event = doubleChestClickEvent(InventoryAction.PICKUP_ALL, 30,
                ironStack(16), null);

        listener.onInventoryClick(event);

        assertThat(recorder.records).hasSize(1);
        ContainerWithdrawRecord record = (ContainerWithdrawRecord) recorder.records.get(0);
        assertThat(record.location().x())
                .as("right-half click attributes to the right block")
                .isEqualTo(2);
        assertThat(record.slot())
                .as("slot 30 re-bases to local slot 3 in the right half")
                .isEqualTo(3);
    }

    @Test
    void serializationIsDeferredToTheExecutor() {
        // #98: the heavy storedItem() build is handed to the serializer, not
        // run on the listener (main) thread. With a queuing executor, no
        // record exists until the deferred task runs.
        List<Runnable> deferred = new java.util.ArrayList<>();
        ContainerTransactionListener deferredListener =
                new ContainerTransactionListener(recorder, support, deferred::add, nextTick::add);
        InventoryClickEvent event = clickEvent(InventoryAction.PLACE_ALL, 0, null, ironStack(64));

        deferredListener.onInventoryClick(event);

        assertThat(recorder.records).as("record must not be built on the listener thread").isEmpty();
        assertThat(deferred).hasSize(1);

        deferred.forEach(Runnable::run);

        assertThat(recorder.records).hasSize(1);
        ContainerDepositRecord record = (ContainerDepositRecord) recorder.records.get(0);
        assertThat(record.amount()).isEqualTo(64);
        assertThat(record.afterItem()).isNotNull();
        assertThat(record.afterItem().material()).isEqualTo("IRON_INGOT");
    }

    // ── shift-click deposits (#268) ──────────────────────────────

    @Test
    void shiftClickDepositEmitsOnePerSlotRecordAcrossPartialAndEmptySlots() {
        // Chest slot 3 holds 40 iron; the player shift-clicks 64 iron from
        // player-inventory slot 30. Vanilla tops the partial stack up to 64
        // (+24) and drops the remaining 40 into the first empty slot. The
        // record shape carries one slot, so this MUST be two records; the
        // pre-#268 code emitted a single slot=-1 record no rollback path
        // could apply.
        ItemStack partialBefore = ironStack(40);
        Inventory top = mock(Inventory.class);
        when(top.getSize()).thenReturn(27);
        when(top.getItem(3)).thenReturn(partialBefore);
        InventoryClickEvent event = shiftClickFromPlayer(top, 30, ironStack(64));

        listener.onInventoryClick(event);
        assertThat(recorder.records).as("nothing recorded until the click applies").isEmpty();
        assertThat(nextTick).hasSize(1);

        // The click has applied: slot 3 topped up to 64, overflow of 40 in slot 0.
        ItemStack overflow = ironStack(40);
        ItemStack toppedUp = ironStack(64);
        when(top.getItem(0)).thenReturn(overflow);
        when(top.getItem(3)).thenReturn(toppedUp);
        nextTick.forEach(Runnable::run);

        assertThat(recorder.records).hasSize(2);
        ContainerDepositRecord intoEmpty = (ContainerDepositRecord) recorder.records.get(0);
        assertThat(intoEmpty.slot()).isEqualTo(0);
        assertThat(intoEmpty.amount()).isEqualTo(40);
        assertThat(intoEmpty.beforeItem()).isNull();
        assertThat(intoEmpty.afterItem().material()).isEqualTo("IRON_INGOT");
        ContainerDepositRecord intoPartial = (ContainerDepositRecord) recorder.records.get(1);
        assertThat(intoPartial.slot()).isEqualTo(3);
        assertThat(intoPartial.amount()).isEqualTo(24);
        assertThat(intoPartial.beforeItem()).isNotNull();
        // No record anywhere carries the old -1 sentinel.
        assertThat(recorder.records)
                .allSatisfy(r -> assertThat(((ContainerDepositRecord) r).slot()).isNotNegative());
    }

    @Test
    void shiftClickIntoFullContainerRecordsNothing() {
        // Nothing fits, nothing moves. The old code recorded a full-stack
        // deposit that never happened.
        ItemStack full = ironStack(64);
        Inventory top = mock(Inventory.class);
        when(top.getSize()).thenReturn(27);
        for (int i = 0; i < 27; i++) {
            when(top.getItem(i)).thenReturn(full);
        }
        InventoryClickEvent event = shiftClickFromPlayer(top, 30, ironStack(64));

        listener.onInventoryClick(event);
        nextTick.forEach(Runnable::run);

        assertThat(recorder.records).isEmpty();
    }

    @Test
    void shiftClickDepositIgnoresForeignMaterialChanges() {
        // A slot that held a different material cannot be a merge target;
        // a same-tick change there (hopper, another player) is not ours.
        Inventory top = mock(Inventory.class);
        when(top.getSize()).thenReturn(27);
        ItemStack gold = mockStack(Material.GOLD_INGOT, 10);
        when(top.getItem(5)).thenReturn(gold);
        InventoryClickEvent event = shiftClickFromPlayer(top, 30, ironStack(16));

        listener.onInventoryClick(event);
        ItemStack foreignSwap = ironStack(16);
        ItemStack deposit = ironStack(16);
        when(top.getItem(5)).thenReturn(foreignSwap); // foreign swap mid-tick
        when(top.getItem(0)).thenReturn(deposit); // the actual deposit
        nextTick.forEach(Runnable::run);

        assertThat(recorder.records).hasSize(1);
        assertThat(((ContainerDepositRecord) recorder.records.get(0)).slot()).isEqualTo(0);
    }

    @Test
    void shiftClickWithdrawStillRecordsTheRealContainerSlot() {
        // The withdraw direction indexed real container slots all along and
        // must keep doing so, without waiting a tick.
        Inventory top = mock(Inventory.class);
        when(top.getSize()).thenReturn(27);
        ItemStack held = ironStack(16);
        when(top.getItem(3)).thenReturn(held);
        InventoryClickEvent event = shiftClickEvent(top, top, 3, InventoryAction.MOVE_TO_OTHER_INVENTORY);

        listener.onInventoryClick(event);

        assertThat(nextTick).isEmpty();
        assertThat(recorder.records).hasSize(1);
        ContainerWithdrawRecord record = (ContainerWithdrawRecord) recorder.records.get(0);
        assertThat(record.slot()).isEqualTo(3);
        assertThat(record.amount()).isEqualTo(16);
    }

    @Test
    void shiftClickDepositIntoDoubleChestRebasesToTheOwningHalf() {
        // Raw slot 30 of a double chest lives in the right half: the record
        // must attribute to the right block at its local slot 3, exactly as
        // direct clicks do.
        Inventory top = mock(Inventory.class);
        when(top.getSize()).thenReturn(54);
        DoubleChest holder = doubleChestHolder();
        when(top.getHolder(false)).thenReturn(holder);
        InventoryClickEvent event = shiftClickFromPlayer(top, 12, ironStack(8));

        listener.onInventoryClick(event);
        ItemStack landed = ironStack(8);
        when(top.getItem(30)).thenReturn(landed);
        nextTick.forEach(Runnable::run);

        assertThat(recorder.records).hasSize(1);
        ContainerDepositRecord record = (ContainerDepositRecord) recorder.records.get(0);
        assertThat(record.location().x()).isEqualTo(2);
        assertThat(record.slot()).isEqualTo(3);
    }

    // ── fixtures ─────────────────────────────────────────────────

    private InventoryClickEvent clickEvent(InventoryAction action, int slot,
                                           ItemStack slotItem, ItemStack cursor) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(PLAYER_ID);
        when(player.getName()).thenReturn("Alice");

        Block block = mock(Block.class);
        when(world.getUID()).thenReturn(UUID.fromString("77777777-7777-7777-7777-777777777777"));
        when(world.getName()).thenReturn("world");
        when(block.getLocation()).thenReturn(new Location(world, 1, 64, 2));
        when(block.getType()).thenReturn(Material.CHEST);
        Chest holder = mock(Chest.class);
        when(holder.getBlock()).thenReturn(block);

        Inventory inventory = mock(Inventory.class);
        when(inventory.getHolder(false)).thenReturn(holder); // #210: listener reads getHolder(false)
        when(inventory.getItem(slot)).thenReturn(slotItem);

        InventoryClickEvent event = mock(InventoryClickEvent.class);
        when(event.getWhoClicked()).thenReturn(player);
        when(event.getClickedInventory()).thenReturn(inventory);
        when(event.getAction()).thenReturn(action);
        when(event.getSlot()).thenReturn(slot);
        when(event.getCursor()).thenReturn(cursor);
        return event;
    }

    /**
     * A click on a double chest: holder is a {@link DoubleChest} whose two
     * halves are {@link Chest} blocks at x=1 (left) and x=2 (right), each a
     * 27-slot block inventory. The clicked (combined) inventory presents the
     * raw slot the player touched.
     */
    private InventoryClickEvent doubleChestClickEvent(InventoryAction action, int rawSlot,
                                                      ItemStack slotItem, ItemStack cursor) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(PLAYER_ID);
        when(player.getName()).thenReturn("Alice");

        when(world.getUID()).thenReturn(UUID.fromString("77777777-7777-7777-7777-777777777777"));
        when(world.getName()).thenReturn("world");

        Chest left = chestHalf(1);
        Chest right = chestHalf(2);
        DoubleChest doubleChest = mock(DoubleChest.class);
        when(doubleChest.getLeftSide()).thenReturn(left);
        when(doubleChest.getRightSide()).thenReturn(right);

        Inventory inventory = mock(Inventory.class);
        when(inventory.getHolder(false)).thenReturn(doubleChest); // #210: getHolder(false)
        when(inventory.getItem(rawSlot)).thenReturn(slotItem);

        InventoryClickEvent event = mock(InventoryClickEvent.class);
        when(event.getWhoClicked()).thenReturn(player);
        when(event.getClickedInventory()).thenReturn(inventory);
        when(event.getAction()).thenReturn(action);
        when(event.getSlot()).thenReturn(rawSlot);
        when(event.getCursor()).thenReturn(cursor);
        return event;
    }

    /**
     * A MOVE_TO_OTHER_INVENTORY click from the player (bottom) inventory
     * into {@code top}. The moved stack sits in player-inventory
     * {@code playerSlot}; {@code top} gets a single-chest holder at
     * (1,64,2) unless the test stubbed one already.
     */
    private InventoryClickEvent shiftClickFromPlayer(Inventory top, int playerSlot, ItemStack moved) {
        Inventory bottom = mock(Inventory.class);
        when(bottom.getItem(playerSlot)).thenReturn(moved);
        return shiftClickEvent(top, bottom, playerSlot, InventoryAction.MOVE_TO_OTHER_INVENTORY);
    }

    private InventoryClickEvent shiftClickEvent(Inventory top, Inventory clicked, int slot,
                                                InventoryAction action) {
        ensureChestHolder(top);
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(PLAYER_ID);
        when(player.getName()).thenReturn("Alice");
        Inventory bottom = clicked.equals(top) ? mock(Inventory.class) : clicked;
        org.bukkit.inventory.InventoryView view = mock(org.bukkit.inventory.InventoryView.class);
        when(view.getTopInventory()).thenReturn(top);
        when(view.getBottomInventory()).thenReturn(bottom);
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        when(event.getWhoClicked()).thenReturn(player);
        when(event.getClickedInventory()).thenReturn(clicked);
        when(event.getAction()).thenReturn(action);
        when(event.getView()).thenReturn(view);
        when(event.getSlot()).thenReturn(slot);
        return event;
    }

    private void ensureChestHolder(Inventory top) {
        if (top.getHolder(false) != null) {
            return;
        }
        when(world.getUID()).thenReturn(UUID.fromString("77777777-7777-7777-7777-777777777777"));
        when(world.getName()).thenReturn("world");
        Block block = mock(Block.class);
        when(block.getLocation()).thenReturn(new Location(world, 1, 64, 2));
        when(block.getType()).thenReturn(Material.CHEST);
        Chest holder = mock(Chest.class);
        when(holder.getBlock()).thenReturn(block);
        when(top.getHolder(false)).thenReturn(holder);
    }

    private DoubleChest doubleChestHolder() {
        when(world.getUID()).thenReturn(UUID.fromString("77777777-7777-7777-7777-777777777777"));
        when(world.getName()).thenReturn("world");
        Chest left = chestHalf(1);
        Chest right = chestHalf(2);
        DoubleChest doubleChest = mock(DoubleChest.class);
        when(doubleChest.getLeftSide()).thenReturn(left);
        when(doubleChest.getRightSide()).thenReturn(right);
        return doubleChest;
    }

    /** A 27-slot chest block at the given x, used as one half of a double chest. */
    private Chest chestHalf(int x) {
        Block block = mock(Block.class);
        when(block.getLocation()).thenReturn(new Location(world, x, 64, 2));
        when(block.getType()).thenReturn(Material.CHEST);
        Inventory blockInv = mock(Inventory.class);
        when(blockInv.getSize()).thenReturn(27);
        Chest chest = mock(Chest.class);
        when(chest.getBlock()).thenReturn(block);
        when(chest.getBlockInventory()).thenReturn(blockInv);
        return chest;
    }

    private static ItemStack ironStack(int amount) {
        return mockStack(Material.IRON_INGOT, amount);
    }

    private static ItemStack mockStack(Material material, int amount) {
        ItemStack stack = mock(ItemStack.class);
        when(stack.getType()).thenReturn(material);
        when(stack.getAmount()).thenReturn(amount);
        when(stack.getMaxStackSize()).thenReturn(64);
        when(stack.getItemMeta()).thenReturn(null);
        when(stack.serializeAsBytes()).thenReturn(new byte[]{1, 2, 3});
        ItemStack clone = mock(ItemStack.class);
        when(clone.getType()).thenReturn(material);
        when(clone.getMaxStackSize()).thenReturn(64);
        when(clone.getItemMeta()).thenReturn(null);
        when(clone.serializeAsBytes()).thenReturn(new byte[]{1, 2, 3});
        // setAmount is recorded but mocks don't mutate; tests assert
        // presence/material, the amount assertions use the record's own
        // amount field.
        when(clone.getAmount()).thenReturn(amount);
        when(stack.clone()).thenReturn(clone);
        return stack;
    }

    private static final class CapturingRecorder implements Recorder {
        final List<EventRecord> records = new java.util.ArrayList<>();

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
