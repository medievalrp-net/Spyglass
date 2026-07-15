package net.medievalrp.spyglass.plugin.rollback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import net.medievalrp.spyglass.api.event.ContainerDepositRecord;
import net.medievalrp.spyglass.api.event.Origin;
import net.medievalrp.spyglass.api.event.Source;
import net.medievalrp.spyglass.api.event.StoredItem;
import net.medievalrp.spyglass.api.rollback.RollbackEffect;
import net.medievalrp.spyglass.api.rollback.RollbackReason;
import net.medievalrp.spyglass.api.rollback.RollbackResult;
import net.medievalrp.spyglass.api.util.BlockLocation;
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
 * #287: containers and entities are opt-in per job. Without the
 * inclusions, container slot writes and entity spawn/removal skip with
 * the flag-naming messages; with them (and for direct engine callers,
 * whose default is include-everything), behavior is unchanged. The
 * container-BLOCK gate needs the live material registry and is covered
 * by in-game verification.
 */
class RollbackInclusionFlagsTest {

    private static final UUID WORLD_ID = UUID.fromString("77777777-7777-7777-7777-777777777777");

    private static RollbackEffect depositRevert() {
        Instant now = Instant.now();
        // afterItem built by the real serializer over the same stubbed
        // bytes the live mock serializes to, so the expected-state guard
        // passes when the gate lets the write through.
        StoredItem after = net.medievalrp.spyglass.plugin.util.ItemSerialization
                .storedItem(0, ironStack());
        return new ContainerDepositRecord(
                UUID.randomUUID(), "deposit", now, now.plusSeconds(3600),
                Origin.player(), Source.player(UUID.randomUUID(), "Alice"),
                new BlockLocation(WORLD_ID, "world", 1, 64, 2),
                "test", "DIAMOND", "CHEST", 0, 40,
                null, after).rollbackEffect();
    }

    private static ItemStack ironStack() {
        ItemStack stack = mock(ItemStack.class);
        when(stack.getType()).thenReturn(Material.DIAMOND);
        when(stack.getAmount()).thenReturn(40);
        when(stack.getItemMeta()).thenReturn(null);
        when(stack.serializeAsBytes()).thenReturn(new byte[]{1});
        return stack;
    }

    private List<RollbackResult> apply(RollbackEngine engine, RollbackEffect effect) {
        World world = mock(World.class);
        when(world.getUID()).thenReturn(WORLD_ID);
        when(world.getName()).thenReturn("world");
        Inventory inventory = mock(Inventory.class);
        when(inventory.getSize()).thenReturn(27);
        ItemStack live = ironStack();
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
            return engine.applyAll(List.of(effect), mock(CommandSender.class));
        }
    }

    @Test
    void containerSlotWritesSkipWithoutTheFlagAndNameIt() {
        RollbackEngine engine = new RollbackEngine();
        engine.includeInRollback(false, false);

        RollbackResult result = apply(engine, depositRevert()).get(0);

        assertThat(result).isInstanceOf(RollbackResult.Skipped.class);
        assertThat(((RollbackReason.NotSupported) ((RollbackResult.Skipped) result).reason()).detail())
                .isEqualTo(RollbackEngine.CONTAINERS_SKIP)
                .contains("--containers");
    }

    @Test
    void missingContainerSkipTellsTheOperatorWhatToDo() {
        // #335: the target cell no longer holds a container (it was broken).
        // Include-everything engine so the #287 gate is not what skips here.
        RollbackEngine engine = new RollbackEngine();

        World world = mock(World.class);
        when(world.getUID()).thenReturn(WORLD_ID);
        when(world.getName()).thenReturn("world");
        Block block = mock(Block.class);
        when(block.getState()).thenReturn(mock(org.bukkit.block.BlockState.class));
        when(world.getBlockAt(1, 64, 2)).thenReturn(block);

        PluginManager pm = mock(PluginManager.class);
        when(pm.getPlugin("FastAsyncWorldEdit")).thenReturn(null);
        when(pm.getPlugin("WorldEdit")).thenReturn(null);

        RollbackResult result;
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            bukkit.when(Bukkit::getPluginManager).thenReturn(pm);
            bukkit.when(() -> Bukkit.getWorld(WORLD_ID)).thenReturn(world);
            bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
            result = engine.applyAll(List.of(depositRevert()), mock(CommandSender.class)).get(0);
        }

        assertThat(result).isInstanceOf(RollbackResult.Skipped.class);
        assertThat(((RollbackReason.NotSupported) ((RollbackResult.Skipped) result).reason()).detail())
                .isEqualTo(RollbackEngine.MISSING_CONTAINER_SKIP)
                .contains("place a container")
                .contains("roll back the area");
    }

    @Test
    void entityWorkSkipsWithoutTheFlagAndNamesIt() {
        RollbackEngine engine = new RollbackEngine();
        engine.includeInRollback(false, false);

        RollbackEffect remove = new RollbackEffect.EntityRemove(
                new BlockLocation(WORLD_ID, "world", 1, 64, 2), "sheep", UUID.randomUUID().toString());
        RollbackResult result = apply(engine, remove).get(0);

        assertThat(result).isInstanceOf(RollbackResult.Skipped.class);
        assertThat(((RollbackReason.NotSupported) ((RollbackResult.Skipped) result).reason()).detail())
                .isEqualTo(RollbackEngine.ENTITIES_SKIP)
                .contains("--entities");
    }

    @Test
    void inclusionsRestoreCurrentBehaviorAndDirectCallersDefaultToInclude() {
        // With the flag (or for a direct caller who never set inclusions),
        // the deposit revert applies exactly as before #287.
        RollbackEngine flagged = new RollbackEngine();
        flagged.includeInRollback(true, true);
        assertThat(apply(flagged, depositRevert()).get(0))
                .isInstanceOf(RollbackResult.Applied.class);

        assertThat(apply(new RollbackEngine(), depositRevert()).get(0))
                .as("engine default is include-everything (tests, legacy undo replay)")
                .isInstanceOf(RollbackResult.Applied.class);
    }

    @Test
    void inclusionsResetToDefaultsWhenTheJobCompletes() {
        RollbackEngine engine = new RollbackEngine();
        engine.includeInRollback(false, false);
        assertThat(apply(engine, depositRevert()).get(0))
                .isInstanceOf(RollbackResult.Skipped.class);

        // The completed job reset the per-job state: the next job includes.
        assertThat(apply(engine, depositRevert()).get(0))
                .as("exclusion never leaks into the next job")
                .isInstanceOf(RollbackResult.Applied.class);
    }
}
