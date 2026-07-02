package net.medievalrp.spyglass.plugin.salvage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.Chunk;
import org.bukkit.block.Barrel;
import org.bukkit.block.Block;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Pins the deferred-serialization contract for container salvage (#207): the
 * heavy {@code serializeAsBytes} + base64 must run on the persist executor for
 * only the genuinely-destroyed containers, never on the main-thread
 * onChunkResolved / onChunkWritten hooks (which now only clone and compare).
 */
class SalvageCapturerTest {

    private static final UUID WORLD = UUID.fromString("00000000-0000-0000-0000-0000000000ab");
    private static final int CX = 4;
    private static final int CZ = -2;
    private static final int BX = 70;
    private static final int BY = 64;
    private static final int BZ = -20;

    private final SalvageStore store = mock(SalvageStore.class);
    private final List<Runnable> deferred = new ArrayList<>();
    private final Executor collecting = deferred::add;
    private final SalvageCapturer capturer =
            new SalvageCapturer(store, collecting, Logger.getLogger("test"));

    private ItemStack diamond() {
        ItemStack stack = mock(ItemStack.class);
        when(stack.getType()).thenReturn(Material.DIAMOND);
        when(stack.getAmount()).thenReturn(1);
        when(stack.hasItemMeta()).thenReturn(false);
        when(stack.serializeAsBytes()).thenReturn(new byte[] {1, 2, 3});
        when(stack.clone()).thenReturn(stack); // captured clone == the same reference
        return stack;
    }

    /** A barrel holding one diamond, wired as the only tile entity in the chunk. */
    private ItemStack wireBarrelWithDiamond(World world, Chunk chunk) {
        ItemStack diamond = diamond();
        Inventory inv = mock(Inventory.class);
        when(inv.getContents()).thenReturn(new ItemStack[] {diamond, null, null});
        Barrel barrel = mock(Barrel.class);
        when(barrel.getX()).thenReturn(BX);
        when(barrel.getY()).thenReturn(BY);
        when(barrel.getZ()).thenReturn(BZ);
        when(barrel.getType()).thenReturn(Material.BARREL);
        when(barrel.getInventory()).thenReturn(inv);
        when(chunk.getTileEntities(false))
                .thenReturn(new org.bukkit.block.BlockState[] {barrel});
        return diamond;
    }

    @Test
    void destroyedContainerSerializesAndSavesOnlyViaTheExecutor() {
        World world = mock(World.class);
        when(world.getUID()).thenReturn(WORLD);
        when(world.getName()).thenReturn("world");
        when(world.isChunkLoaded(CX, CZ)).thenReturn(true);
        Chunk chunk = mock(Chunk.class);
        when(world.getChunkAt(CX, CZ)).thenReturn(chunk);
        wireBarrelWithDiamond(world, chunk);
        // After the rollback, the barrel cell is now stone -> destroyed.
        Block block = mock(Block.class);
        when(block.getType()).thenReturn(Material.STONE);
        when(world.getBlockAt(BX, BY, BZ)).thenReturn(block);

        capturer.begin("Alice", UUID.randomUUID());
        capturer.onChunkResolved(world, CX, CZ);
        capturer.onChunkWritten(world, CX, CZ);

        // #207: nothing serialized or saved on the main-thread hooks - the work
        // is queued to the persist executor.
        verifyNoInteractions(store);
        assertThat(deferred).hasSize(1);

        deferred.get(0).run();

        ArgumentCaptor<SalvageSnapshot> saved = ArgumentCaptor.forClass(SalvageSnapshot.class);
        verify(store).save(saved.capture());
        assertThat(saved.getValue().items())
                .as("the destroyed barrel's one diamond is serialized off-main and saved")
                .hasSize(1);
        assertThat(saved.getValue().x()).isEqualTo(BX);
        assertThat(saved.getValue().containerType()).isEqualTo("BARREL");
    }

    @Test
    void survivingContainerIsNeverSaved() {
        World world = mock(World.class);
        when(world.getUID()).thenReturn(WORLD);
        when(world.getName()).thenReturn("world");
        when(world.isChunkLoaded(CX, CZ)).thenReturn(true);
        Chunk chunk = mock(Chunk.class);
        when(world.getChunkAt(CX, CZ)).thenReturn(chunk);
        ItemStack diamond = wireBarrelWithDiamond(world, chunk);
        // After the rollback the barrel is still a barrel with the same diamond.
        Block block = mock(Block.class);
        when(block.getType()).thenReturn(Material.BARREL);
        Barrel liveState = mock(Barrel.class);
        Inventory liveInv = mock(Inventory.class);
        when(liveInv.getContents()).thenReturn(new ItemStack[] {diamond, null, null});
        when(liveState.getInventory()).thenReturn(liveInv);
        when(block.getState()).thenReturn(liveState);
        when(world.getBlockAt(BX, BY, BZ)).thenReturn(block);

        capturer.begin("Alice", UUID.randomUUID());
        capturer.onChunkResolved(world, CX, CZ);
        capturer.onChunkWritten(world, CX, CZ);

        assertThat(deferred).as("an unchanged container queues no persist work").isEmpty();
        verify(store, never()).save(org.mockito.ArgumentMatchers.any());
    }
}
