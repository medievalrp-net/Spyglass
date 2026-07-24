package net.medievalrp.spyglass.plugin.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import net.medievalrp.spyglass.api.event.StoredItem;
import net.medievalrp.spyglass.plugin.util.ItemSerialization;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/**
 * The shared extract engine behind both {@code /sg snapshot} surfaces
 * (#341): the permission re-check, the refuse-whole-not-partial fit check,
 * and the audit hand-off.
 */
class SnapshotTakesTest {

    private static StoredItem item(int slot, String blob) {
        return new StoredItem(slot, "DIAMOND", blob);
    }

    private static SnapshotSession playerSession(int slot, int count, String blob) {
        return new SnapshotSession(java.util.UUID.randomUUID(), SnapshotSession.Kind.PLAYER, "Steve",
                Instant.EPOCH, Instant.EPOCH, PlayerSnapshot.CAUSE_JOIN, SnapshotSession.Certainty.CERTAIN,
                List.of(), 6, List.of(new SnapshotSlot(slot, count, item(slot, blob))));
    }

    private static SnapshotSession containerSession(int slot, String blob) {
        return new SnapshotSession(java.util.UUID.randomUUID(), SnapshotSession.Kind.CONTAINER, "world 1,2,3 CHEST",
                Instant.EPOCH, Instant.EPOCH, null, SnapshotSession.Certainty.CERTAIN,
                List.of(), 3, List.of(new SnapshotSlot(slot, 0, item(slot, blob))));
    }

    /** A mock ItemStack whose amount actually changes when {@code setAmount}
     *  is called - a plain stub would leave getAmount() frozen, which would
     *  hide the exact bug this class exists to avoid (calling setAmount on
     *  a container-mode slot). */
    private static ItemStack mutableStack(Material material, int initialAmount, int maxStackSize) {
        ItemStack stack = mock(ItemStack.class);
        int[] amount = {initialAmount};
        when(stack.getType()).thenReturn(material);
        when(stack.getMaxStackSize()).thenReturn(maxStackSize);
        when(stack.getAmount()).thenAnswer(inv -> amount[0]);
        doAnswer(inv -> {
            amount[0] = inv.getArgument(0);
            return null;
        }).when(stack).setAmount(anyInt());
        when(stack.clone()).thenReturn(stack);
        when(stack.isSimilar(any())).thenReturn(false);
        return stack;
    }

    private static Player playerWith(PlayerInventory inventory, boolean canTake) {
        Player player = mock(Player.class);
        when(player.hasPermission(SnapshotTakes.PERMISSION)).thenReturn(canTake);
        when(player.getInventory()).thenReturn(inventory);
        return player;
    }

    private static PlayerInventory inventoryWithStorage(ItemStack[] storageContents) {
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(inventory.getStorageContents()).thenReturn(storageContents);
        return inventory;
    }

    // ---- permission check --------------------------------------------------

    @Test
    void withoutPermissionRefusesAndTouchesNothing() {
        SnapshotTakeLogger logger = mock(SnapshotTakeLogger.class);
        SnapshotTakes takes = new SnapshotTakes(logger);
        PlayerInventory inventory = inventoryWithStorage(new ItemStack[36]);
        Player player = playerWith(inventory, false);
        SnapshotSession session = playerSession(0, 64, "blob");

        SnapshotTakes.Result result = takes.take(player, session, 0);

        assertThat(result).isEqualTo(SnapshotTakes.Result.NO_PERMISSION);
        verify(inventory, never()).addItem(any(ItemStack.class));
        verify(logger, never()).log(any(), any(), any(), anyInt());
    }

    // ---- slot resolution -----------------------------------------------

    @Test
    void missingSlotIsRefused() {
        SnapshotTakes takes = new SnapshotTakes(null);
        PlayerInventory inventory = inventoryWithStorage(new ItemStack[36]);
        Player player = playerWith(inventory, true);
        SnapshotSession session = playerSession(5, 64, "blob");

        SnapshotTakes.Result result = takes.take(player, session, 12);

        assertThat(result).isEqualTo(SnapshotTakes.Result.SLOT_EMPTY);
        verify(inventory, never()).addItem(any(ItemStack.class));
    }

    @Test
    void decodeReturningNullIsRefused() {
        SnapshotTakes takes = new SnapshotTakes(null);
        PlayerInventory inventory = inventoryWithStorage(new ItemStack[36]);
        Player player = playerWith(inventory, true);
        SnapshotSession session = playerSession(0, 64, "blob");

        try (MockedStatic<ItemSerialization> serialization = mockStatic(ItemSerialization.class)) {
            serialization.when(() -> ItemSerialization.decode(anyString())).thenReturn(null);

            SnapshotTakes.Result result = takes.take(player, session, 0);

            assertThat(result).isEqualTo(SnapshotTakes.Result.SLOT_EMPTY);
            verify(inventory, never()).addItem(any(ItemStack.class));
        }
    }

    // ---- refusal on full: no partial, no ground drop -------------------

    @Test
    void wholeStackRefusedWhenInventoryCannotFitItAndNothingIsAddedOrLogged() {
        SnapshotTakeLogger logger = mock(SnapshotTakeLogger.class);
        SnapshotTakes takes = new SnapshotTakes(logger);

        // 36 full, dissimilar slots: no merge room, no empty slot.
        ItemStack[] full = new ItemStack[36];
        for (int i = 0; i < full.length; i++) {
            full[i] = mutableStack(Material.DIRT, 64, 64);
        }
        PlayerInventory inventory = inventoryWithStorage(full);
        Player player = playerWith(inventory, true);
        SnapshotSession session = playerSession(0, 64, "blob");
        ItemStack decoded = mutableStack(Material.DIAMOND, 1, 64);

        try (MockedStatic<ItemSerialization> serialization = mockStatic(ItemSerialization.class)) {
            serialization.when(() -> ItemSerialization.decode(anyString())).thenReturn(decoded);

            SnapshotTakes.Result result = takes.take(player, session, 0);

            assertThat(result).isEqualTo(SnapshotTakes.Result.INVENTORY_FULL);
            verify(inventory, never()).addItem(any(ItemStack.class));
            verify(logger, never()).log(any(), any(), any(), anyInt());
        }
    }

    // ---- success + audit -------------------------------------------------

    @Test
    void successfulTakeAddsACloneAndLogsTheAudit() {
        SnapshotTakeLogger logger = mock(SnapshotTakeLogger.class);
        SnapshotTakes takes = new SnapshotTakes(logger);
        PlayerInventory inventory = inventoryWithStorage(new ItemStack[36]); // all empty
        Player player = playerWith(inventory, true);
        SnapshotSession session = playerSession(0, 64, "blob");
        ItemStack decoded = mutableStack(Material.DIAMOND, 1, 64);

        try (MockedStatic<ItemSerialization> serialization = mockStatic(ItemSerialization.class)) {
            serialization.when(() -> ItemSerialization.decode("blob")).thenReturn(decoded);

            SnapshotTakes.Result result = takes.take(player, session, 0);

            assertThat(result).isEqualTo(SnapshotTakes.Result.TAKEN);
            verify(inventory, times(1)).addItem(any(ItemStack.class));
            verify(logger, times(1)).log(eq(player), eq(session), any(ItemStack.class), eq(0));
        }
    }

    @Test
    void aNullLoggerNeverThrowsOnASuccessfulTake() {
        SnapshotTakes takes = new SnapshotTakes(null);
        PlayerInventory inventory = inventoryWithStorage(new ItemStack[36]);
        Player player = playerWith(inventory, true);
        SnapshotSession session = playerSession(0, 64, "blob");
        ItemStack decoded = mutableStack(Material.DIAMOND, 1, 64);

        try (MockedStatic<ItemSerialization> serialization = mockStatic(ItemSerialization.class)) {
            serialization.when(() -> ItemSerialization.decode("blob")).thenReturn(decoded);

            SnapshotTakes.Result result = takes.take(player, session, 0);

            assertThat(result).isEqualTo(SnapshotTakes.Result.TAKEN);
        }
    }

    // ---- count semantics: player vs container mode -----------------------

    @Test
    void playerModeOverwritesTheDecodedAmountWithTheSlotCount() {
        SnapshotTakes takes = new SnapshotTakes(null);
        PlayerInventory inventory = inventoryWithStorage(new ItemStack[36]);
        Player player = playerWith(inventory, true);
        // The interned blob normalizes to amount 1; the real count (64)
        // lives in the slot, per SnapshotSlot's javadoc.
        SnapshotSession session = playerSession(0, 64, "blob");
        ItemStack decoded = mutableStack(Material.DIAMOND, 1, 64);

        try (MockedStatic<ItemSerialization> serialization = mockStatic(ItemSerialization.class)) {
            serialization.when(() -> ItemSerialization.decode("blob")).thenReturn(decoded);

            takes.take(player, session, 0);

            assertThat(decoded.getAmount()).isEqualTo(64);
        }
    }

    @Test
    void containerModeNeverCallsSetAmountOnTheDecodedStack() {
        SnapshotTakes takes = new SnapshotTakes(null);
        PlayerInventory inventory = inventoryWithStorage(new ItemStack[36]);
        Player player = playerWith(inventory, true);
        // COUNT_IN_BLOB (0) is a sentinel, not a real amount - the real
        // count already lives inside the decoded blob (5 here).
        SnapshotSession session = containerSession(0, "blob");
        ItemStack decoded = mutableStack(Material.DIAMOND, 5, 64);

        try (MockedStatic<ItemSerialization> serialization = mockStatic(ItemSerialization.class)) {
            serialization.when(() -> ItemSerialization.decode("blob")).thenReturn(decoded);

            takes.take(player, session, 0);

            assertThat(decoded.getAmount()).isEqualTo(5);
        }
    }

    // ---- fits(): merge-then-empty-slot simulation -------------------------

    @Test
    void fitsMergesIntoAPartialExistingStackBeforeNeedingAnEmptySlot() {
        ItemStack partialDiamonds = mutableStack(Material.DIAMOND, 60, 64);
        when(partialDiamonds.isSimilar(any())).thenReturn(true); // same type as candidate
        ItemStack[] storage = new ItemStack[36];
        storage[0] = partialDiamonds; // 4 slots of room, no empty slots at all
        ItemStack candidate = mutableStack(Material.DIAMOND, 4, 64);

        assertThat(SnapshotTakes.fits(storage, candidate)).isTrue();
    }

    @Test
    void fitsRefusesWhenThePartialStackCannotCoverTheWholeAmountAndNoSlotIsEmpty() {
        ItemStack partialDiamonds = mutableStack(Material.DIAMOND, 60, 64);
        when(partialDiamonds.isSimilar(any())).thenReturn(true);
        ItemStack[] storage = new ItemStack[36];
        for (int i = 0; i < storage.length; i++) {
            storage[i] = mutableStack(Material.DIRT, 64, 64); // full, unrelated, no empties
        }
        storage[0] = partialDiamonds; // only 4 slots of room
        ItemStack candidate = mutableStack(Material.DIAMOND, 5, 64); // needs 5

        assertThat(SnapshotTakes.fits(storage, candidate)).isFalse();
    }

    @Test
    void fitsFallsThroughToAnEmptySlotWhenNoExistingStackHasRoom() {
        ItemStack[] storage = new ItemStack[36]; // every slot empty
        ItemStack candidate = mutableStack(Material.DIAMOND, 64, 64);

        assertThat(SnapshotTakes.fits(storage, candidate)).isTrue();
    }
}
