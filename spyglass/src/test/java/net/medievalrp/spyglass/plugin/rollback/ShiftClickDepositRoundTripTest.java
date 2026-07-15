package net.medievalrp.spyglass.plugin.rollback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import net.medievalrp.spyglass.api.event.ContainerDepositRecord;
import net.medievalrp.spyglass.api.event.Origin;
import net.medievalrp.spyglass.api.event.Source;
import net.medievalrp.spyglass.api.event.StoredItem;
import net.medievalrp.spyglass.api.rollback.RollbackEffect;
import net.medievalrp.spyglass.api.rollback.RollbackResult;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.plugin.util.ItemSerialization;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.command.CommandSender;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/**
 * Regression test for #268 (engine side): the per-slot records the
 * shift-click deposit path now emits round-trip through
 * {@link RollbackEngine} and actually clear the deposited items. The
 * pre-fix slot=-1 shape was rejected by every container apply path as a
 * benign NotSupported skip.
 */
class ShiftClickDepositRoundTripTest {

    private static final UUID WORLD_ID = UUID.fromString("77777777-7777-7777-7777-777777777777");

    @Test
    void perSlotDepositRecordAppliesAndClearsTheSlot() {
        // The record the fixed listener emits for a deposit into an empty
        // slot: real container slot, before=null, after=the merged stack.
        ItemStack deposited = ironStack(40);
        StoredItem after = ItemSerialization.storedItem(0, deposited);
        Instant now = Instant.now();
        ContainerDepositRecord record = new ContainerDepositRecord(
                UUID.randomUUID(), "deposit", now, now.plusSeconds(3600),
                Origin.player(), Source.player(UUID.randomUUID(), "Alice"),
                new BlockLocation(WORLD_ID, "world", 1, 64, 2),
                "test", "IRON_INGOT", "CHEST", 0, 40, null, after);
        RollbackEffect effect = record.rollbackEffect();
        assertThat(((RollbackEffect.ContainerSlotWrite) effect).slot()).isEqualTo(0);

        World world = mock(World.class);
        when(world.getUID()).thenReturn(WORLD_ID);
        when(world.getName()).thenReturn("world");
        Inventory inventory = mock(Inventory.class);
        when(inventory.getSize()).thenReturn(27);
        // Live slot still holds exactly what the record said the deposit
        // left behind, so the expected-state guard passes.
        ItemStack live = ironStack(40);
        when(inventory.getItem(0)).thenReturn(live);
        Container container = mock(Container.class);
        when(container.getInventory()).thenReturn(inventory);
        when(container.getSnapshotInventory()).thenReturn(inventory);
        Block block = mock(Block.class);
        when(block.getState()).thenReturn(container);
        when(world.getBlockAt(1, 64, 2)).thenReturn(block);

        PluginManager pm = mock(PluginManager.class);
        when(pm.getPlugin("FastAsyncWorldEdit")).thenReturn(null);
        when(pm.getPlugin("WorldEdit")).thenReturn(null);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            bukkit.when(Bukkit::getPluginManager).thenReturn(pm);
            bukkit.when(() -> Bukkit.getWorld(WORLD_ID)).thenReturn(world);
            bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);

            RollbackEngine engine = new RollbackEngine();
            RollbackResult result = engine.applyAll(List.of(effect), mock(CommandSender.class)).get(0);

            assertThat(result)
                    .as("a real-slot deposit record must apply, not skip")
                    .isInstanceOf(RollbackResult.Applied.class);
            verify(inventory).setItem(0, null);
        }
    }

    private static ItemStack ironStack(int amount) {
        ItemStack stack = mock(ItemStack.class);
        when(stack.getType()).thenReturn(Material.IRON_INGOT);
        when(stack.getAmount()).thenReturn(amount);
        when(stack.getMaxStackSize()).thenReturn(64);
        when(stack.getItemMeta()).thenReturn(null);
        when(stack.serializeAsBytes()).thenReturn(new byte[]{9, 9, 9});
        return stack;
    }
}
