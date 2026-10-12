package net.medievalrp.spyglass.plugin.salvage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.logging.Logger;
import net.medievalrp.spyglass.api.event.StoredItem;
import net.medievalrp.spyglass.api.capture.ItemSerialization;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/**
 * The dupe guard shared by the GUI and the command: a slot is marked in-flight
 * before its store write is dispatched, and a second take on that slot during
 * the write window is refused. This is the exploit the {@link InFlightTracker}
 * exists to close, so it is pinned directly on the shared engine.
 */
class SalvageWithdrawalsTest {

    private static SalvageSnapshot snapshotWithOneItem(UUID id, int slot) {
        return new SalvageSnapshot(id, UUID.randomUUID(), UUID.randomUUID(), "world",
                1, 64, 1, "CHEST", "Alice", Instant.EPOCH,
                List.of(new StoredItem(slot, "DIAMOND", "blob")));
    }

    private static Player playerWithRoom() {
        Player player = mock(Player.class);
        PlayerInventory inv = mock(PlayerInventory.class);
        when(player.getInventory()).thenReturn(inv);
        when(player.isOnline()).thenReturn(true);
        when(inv.addItem(any(ItemStack.class))).thenReturn(new HashMap<>()); // all placed
        return player;
    }

    private static ItemStack oneDiamond() {
        ItemStack stack = mock(ItemStack.class);
        when(stack.getType()).thenReturn(Material.DIAMOND);
        when(stack.getAmount()).thenReturn(1);
        when(stack.clone()).thenReturn(stack);
        return stack;
    }

    @Test
    void secondTakeOnAnInFlightSlotIsRefused() {
        SalvageStore store = mock(SalvageStore.class);
        // Manual executor: hold the store write so the slot stays in-flight.
        List<Runnable> pending = new ArrayList<>();
        Executor held = pending::add;
        SalvageWithdrawals withdrawals =
                new SalvageWithdrawals(store, held, null, Logger.getLogger("test"));

        UUID id = UUID.randomUUID();
        SalvageSnapshot snap = snapshotWithOneItem(id, 5);
        when(store.get(id)).thenReturn(java.util.Optional.of(snap));
        Player player = playerWithRoom();
        ItemStack diamond = oneDiamond();

        try (MockedStatic<ItemSerialization> ms = mockStatic(ItemSerialization.class)) {
            ms.when(() -> ItemSerialization.decode(anyString())).thenReturn(diamond);

            SalvageWithdrawals.Outcome first = withdrawals.withdraw(player, snap, 0);
            assertThat(first.status()).isEqualTo(SalvageWithdrawals.Status.EMPTIED);
            // The write was dispatched, not run, so the slot is still in-flight.
            verify(store, never()).delete(any());

            // Second take on the same slot before the write settles: refused, and
            // the player is not given a second diamond. (The store still returns
            // the old snapshot - the write hasn't landed - so the in-flight mark
            // is what stands between the click and a dupe here.)
            SalvageWithdrawals.Outcome second = withdrawals.withdraw(player, snap, 0);
            assertThat(second.status()).isEqualTo(SalvageWithdrawals.Status.REFUSED);
            verify(player.getInventory()).addItem(any(ItemStack.class)); // exactly once

            // Settle the write: the slot clears and the store is mutated once.
            pending.forEach(Runnable::run);
            verify(store).delete(id);
        }
    }

    // A click burst on a fast local store: the first take's write settles
    // (delete committed, mark cleared) while the second click is mid-read, so
    // the second read still returns the pre-delete row. If the slot were
    // claimed only after that read, the cleared mark would let it pay twice.
    @Test
    void writeSettlingDuringASecondTakesReadCannotPayTwice() {
        SalvageStore store = mock(SalvageStore.class);
        List<Runnable> pending = new ArrayList<>();
        Executor held = pending::add;
        SalvageWithdrawals withdrawals =
                new SalvageWithdrawals(store, held, null, Logger.getLogger("test"));

        UUID id = UUID.randomUUID();
        SalvageSnapshot snap = snapshotWithOneItem(id, 5);
        when(store.get(id)).thenReturn(java.util.Optional.of(snap)).thenAnswer(read -> {
            // The store thread finishes the first take's write during this read.
            pending.forEach(Runnable::run);
            pending.clear();
            return java.util.Optional.of(snap);
        });
        Player player = playerWithRoom();
        ItemStack diamond = oneDiamond();

        try (MockedStatic<ItemSerialization> ms = mockStatic(ItemSerialization.class)) {
            ms.when(() -> ItemSerialization.decode(anyString())).thenReturn(diamond);

            SalvageWithdrawals.Outcome first = withdrawals.withdraw(player, snap, 0);
            assertThat(first.status()).isEqualTo(SalvageWithdrawals.Status.EMPTIED);

            withdrawals.withdraw(player, snap, 0);
            verify(player.getInventory()).addItem(any(ItemStack.class)); // exactly once
        }
    }

    // Two takes of different slots overlapping: each rewrites the snapshot's
    // whole item list from its own read, so the later write could put back the
    // stack the earlier take removed and re-offer it (a lost update, seen on
    // MongoDB as two staff both receiving one stack). The second take must
    // wait until the first take's write has settled.
    @Test
    void overlappingTakesOfDifferentSlotsAreSerialized() {
        SalvageStore store = mock(SalvageStore.class);
        List<Runnable> pending = new ArrayList<>();
        Executor held = pending::add;
        SalvageWithdrawals withdrawals =
                new SalvageWithdrawals(store, held, null, Logger.getLogger("test"));

        UUID id = UUID.randomUUID();
        StoredItem five = new StoredItem(5, "DIAMOND", "blob");
        StoredItem six = new StoredItem(6, "DIAMOND", "blob");
        SalvageSnapshot both = snapshotWithOneItem(id, 5).withItems(List.of(five, six));
        when(store.get(id)).thenReturn(java.util.Optional.of(both));
        Player player = playerWithRoom();
        ItemStack diamond = oneDiamond();

        try (MockedStatic<ItemSerialization> ms = mockStatic(ItemSerialization.class)) {
            ms.when(() -> ItemSerialization.decode(anyString())).thenReturn(diamond);

            assertThat(withdrawals.withdraw(player, both, 0).status())
                    .isEqualTo(SalvageWithdrawals.Status.TAKEN);
            // Slot 6 while slot 5's write is pending: refused, nothing paid.
            assertThat(withdrawals.withdraw(player, both, 1).status())
                    .isEqualTo(SalvageWithdrawals.Status.REFUSED);
            verify(player.getInventory()).addItem(any(ItemStack.class));

            pending.forEach(Runnable::run);
            pending.clear();
            verify(store).replaceItems(id, List.of(six));

            // Once it settles, the next take reads the written state.
            when(store.get(id)).thenReturn(java.util.Optional.of(both.withItems(List.of(six))));
            assertThat(withdrawals.withdraw(player, both, 1).status())
                    .isEqualTo(SalvageWithdrawals.Status.EMPTIED);
            verify(player.getInventory(), times(2)).addItem(any(ItemStack.class));
        }
    }

    // #291: a GUI window that missed its refresh keeps offering a stack
    // that was already taken and persisted. The stale view must not pay
    // again - the store is the authority.
    @Test
    void staleViewCannotTakeAnAlreadyPersistedSlot() {
        SalvageStore store = mock(SalvageStore.class);
        Executor direct = Runnable::run;   // writes settle immediately
        SalvageWithdrawals withdrawals =
                new SalvageWithdrawals(store, direct, null, Logger.getLogger("test"));

        UUID id = UUID.randomUUID();
        SalvageSnapshot snap = snapshotWithOneItem(id, 5);
        // First read sees the item; after the settled delete, the store has
        // nothing left for this snapshot.
        when(store.get(id)).thenReturn(java.util.Optional.of(snap))
                .thenReturn(java.util.Optional.empty());
        Player player = playerWithRoom();
        ItemStack diamond = oneDiamond();

        try (MockedStatic<ItemSerialization> ms = mockStatic(ItemSerialization.class)) {
            ms.when(() -> ItemSerialization.decode(anyString())).thenReturn(diamond);

            SalvageWithdrawals.Outcome first = withdrawals.withdraw(player, snap, 0);
            assertThat(first.status()).isEqualTo(SalvageWithdrawals.Status.EMPTIED);
            verify(store).delete(id);

            // The stale window clicks the same rendered stack again.
            SalvageWithdrawals.Outcome second = withdrawals.withdraw(player, snap, 0);
            assertThat(second.status()).isEqualTo(SalvageWithdrawals.Status.EMPTIED);
            verify(player.getInventory()).addItem(any(ItemStack.class)); // exactly once
        }
    }

    @Test
    void withdrawAllRefusesSlotsAlreadyInFlight() {
        SalvageStore store = mock(SalvageStore.class);
        List<Runnable> pending = new ArrayList<>();
        Executor held = pending::add;
        SalvageWithdrawals withdrawals =
                new SalvageWithdrawals(store, held, null, Logger.getLogger("test"));

        UUID id = UUID.randomUUID();
        SalvageSnapshot snap = snapshotWithOneItem(id, 5);
        when(store.get(id)).thenReturn(java.util.Optional.of(snap));
        Player player = playerWithRoom();
        ItemStack diamond = oneDiamond();

        try (MockedStatic<ItemSerialization> ms = mockStatic(ItemSerialization.class)) {
            ms.when(() -> ItemSerialization.decode(anyString())).thenReturn(diamond);

            SalvageWithdrawals.BulkResult first = withdrawals.withdrawAll(player, snap);
            assertThat(first.itemsTaken()).isEqualTo(1);
            assertThat(first.emptied()).isTrue();

            // Second bulk take before the first write settles: every slot is
            // in-flight, so nothing is taken again.
            SalvageWithdrawals.BulkResult second = withdrawals.withdrawAll(player, snap);
            assertThat(second.itemsTaken()).isZero();
            assertThat(second.stacksTaken()).isZero();
        }
    }
}
