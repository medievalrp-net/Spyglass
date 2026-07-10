package net.medievalrp.spyglass.plugin.rollback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.medievalrp.spyglass.api.event.BlockSnapshot;
import net.medievalrp.spyglass.api.rollback.RollbackEffect;
import net.medievalrp.spyglass.api.rollback.RollbackReason;
import net.medievalrp.spyglass.api.rollback.RollbackResult;
import net.medievalrp.spyglass.api.util.BlockLocation;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/**
 * Regression tests for #264: a cell whose LIVE block is one of the
 * operator's excluded materials is never force-overwritten. Before the
 * fix, block:!chest only dropped the chest's RECORD; older records at
 * the same coordinate still dragged the coordinate back to the window's
 * start state, deleting the live chest with no drops. This is the
 * scoped guard - the general expected-state guard #69 removed stays
 * removed (see the comment at RollbackEngine.protectedMaterials).
 */
class ProtectedLiveGuardTest {

    private static final UUID WORLD_ID = UUID.fromString("77777777-7777-7777-7777-777777777777");

    private static RollbackEffect.BlockReplace replaceAt(int x, Material replacement) {
        BlockLocation loc = new BlockLocation(WORLD_ID, "world", x, 64, 0);
        BlockSnapshot replacementSnap = new BlockSnapshot(replacement,
                "minecraft:" + replacement.name().toLowerCase(java.util.Locale.ROOT),
                List.of(), List.of(), List.of(), List.of(), null);
        return new RollbackEffect.BlockReplace(loc, null, replacementSnap);
    }

    private static World mockWorldWithLive(Material... liveByX) {
        World world = mock(World.class);
        when(world.getUID()).thenReturn(WORLD_ID);
        when(world.getName()).thenReturn("world");
        for (int x = 0; x < liveByX.length; x++) {
            Block block = mock(Block.class);
            when(block.getType()).thenReturn(liveByX[x]);
            BlockData data = mock(BlockData.class);
            when(data.getAsString()).thenReturn("minecraft:" + liveByX[x].name().toLowerCase(java.util.Locale.ROOT));
            when(block.getBlockData()).thenReturn(data);
            when(world.getBlockAt(x, 64, 0)).thenReturn(block);
        }
        return world;
    }

    @Test
    void liveExcludedMaterialIsSkippedOthersStillApply() {
        // Coordinate 0 holds a live CHEST (excluded); coordinate 1 holds
        // GRASS_BLOCK. Both cells have surviving history that would
        // force-write AIR over them.
        World world = mockWorldWithLive(Material.CHEST, Material.GRASS_BLOCK);
        List<RollbackEffect> effects = List.of(
                replaceAt(0, Material.AIR), replaceAt(1, Material.AIR));

        PluginManager pm = mock(PluginManager.class);
        when(pm.getPlugin("FastAsyncWorldEdit")).thenReturn(null);
        when(pm.getPlugin("WorldEdit")).thenReturn(null);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            bukkit.when(Bukkit::getPluginManager).thenReturn(pm);
            bukkit.when(() -> Bukkit.getWorld(WORLD_ID)).thenReturn(world);
            bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
            BlockData replacementData = mock(BlockData.class);
            bukkit.when(() -> Bukkit.createBlockData(org.mockito.ArgumentMatchers.anyString()))
                    .thenReturn(replacementData);

            RollbackEngine engine = new RollbackEngine();
            engine.protectMaterials(Set.of("CHEST"));
            List<RollbackResult> results = engine.applyAll(effects, mock(CommandSender.class));

            assertThat(results.get(0))
                    .as("the live excluded chest must not be overwritten")
                    .isInstanceOf(RollbackResult.Skipped.class);
            RollbackResult.Skipped skipped = (RollbackResult.Skipped) results.get(0);
            assertThat(skipped.reason()).isInstanceOf(RollbackReason.NotSupported.class);
            assertThat(((RollbackReason.NotSupported) skipped.reason()).detail())
                    .isEqualTo(RollbackEngine.PROTECTED_SKIP);
            assertThat(results.get(1))
                    .as("cells whose live block is not excluded still apply")
                    .isInstanceOf(RollbackResult.Applied.class);

            // The guard is per job: the completed run reset it, so the same
            // engine without a new protectMaterials call overwrites freely.
            List<RollbackResult> unguarded = engine.applyAll(
                    List.of(replaceAt(0, Material.AIR)), mock(CommandSender.class));
            assertThat(unguarded.get(0))
                    .as("protection never leaks into the next job")
                    .isInstanceOf(RollbackResult.Applied.class);
        }
    }
}
