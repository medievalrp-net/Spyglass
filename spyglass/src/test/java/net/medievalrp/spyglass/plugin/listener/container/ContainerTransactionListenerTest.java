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
    private final ContainerTransactionListener listener =
            new ContainerTransactionListener(recorder, support);

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
        when(inventory.getHolder()).thenReturn(holder);
        when(inventory.getItem(slot)).thenReturn(slotItem);

        InventoryClickEvent event = mock(InventoryClickEvent.class);
        when(event.getWhoClicked()).thenReturn(player);
        when(event.getClickedInventory()).thenReturn(inventory);
        when(event.getAction()).thenReturn(action);
        when(event.getSlot()).thenReturn(slot);
        when(event.getCursor()).thenReturn(cursor);
        return event;
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
