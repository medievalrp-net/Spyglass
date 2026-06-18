package net.medievalrp.spyglass.plugin.rollback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.medievalrp.spyglass.api.event.BlockSnapshot;
import net.medievalrp.spyglass.api.rollback.RollbackEffect;
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
 * Chaos tests for {@link RollbackEngine}'s bulk apply path.
 *
 * <p>Spyglass rolls back by <b>force-overwrite</b>: the apply path
 * restores every effect to its logged state without first checking
 * whether the live block still matches what was recorded — matching the
 * original Spyglass and CoreProtect. A grief rollback must put the
 * block back even where unlogged drift (water/lava/fire/falling blocks)
 * moved into the gap after the edit. (#69 removed the expected-state
 * skip that had crept into the parallel and columnar paths, so all apply
 * paths now honor this contract; transparency comes from the
 * rolled-place / rolled-break audit records instead.) These tests pin it:
 *
 * <ul>
 * <li>every effect is reported {@link RollbackResult.Applied}, even
 * when the live block has diverged from the recorded snapshot,</li>
 * <li>per-effect results stay positionally aligned with the input
 * list — no cross-talk under batches of 50+ entries,</li>
 * <li>the engine refuses to run off the main server thread.</li>
 * </ul>
 */
class RollbackEngineChaosTest {

    private static final UUID WORLD_ID = UUID.fromString("77777777-7777-7777-7777-777777777777");

    @Test
    void forceOverwriteAppliesEveryEffectRegardlessOfLiveState() {
        int total = 50;
        // Every effect rolls STONE back over the current block. The odd
        // indices are wired so the *live* block has diverged to
        // GRASS_BLOCK — under conflict detection those would skip, but
        // force-overwrite restores them anyway. The alternating layout
        // also proves the engine doesn't misalign results under mixed
        // input.
        List<RollbackEffect> effects = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            BlockLocation loc = new BlockLocation(WORLD_ID, "world", i, 64, 0);
            BlockSnapshot expected = snapshot(Material.STONE, "minecraft:stone");
            BlockSnapshot replacement = snapshot(Material.AIR, "minecraft:air");
            // Constructor: BlockReplace(location, expectedCurrent, replacement)
            effects.add(new RollbackEffect.BlockReplace(loc, expected, replacement));
        }

        World world = mock(World.class);
        when(world.getUID()).thenReturn(WORLD_ID);
        when(world.getName()).thenReturn("world");

        // Per-effect block mocks: even index → live state matches the
        // recorded snapshot, odd index → live state has diverged. The
        // engine never reads these for a conflict check (force-overwrite),
        // so both must still come back Applied.
        for (int i = 0; i < total; i++) {
            boolean liveMatches = (i % 2) == 0;
            Block block = mock(Block.class);
            when(block.getType()).thenReturn(liveMatches ? Material.STONE : Material.GRASS_BLOCK);
            BlockData blockData = mock(BlockData.class);
            when(blockData.getAsString()).thenReturn(
                    liveMatches ? "minecraft:stone" : "minecraft:grass_block");
            when(block.getBlockData()).thenReturn(blockData);
            when(world.getBlockAt(i, 64, 0)).thenReturn(block);
        }

        PluginManager pm = mock(PluginManager.class);
        when(pm.getPlugin("FastAsyncWorldEdit")).thenReturn(null);
        when(pm.getPlugin("WorldEdit")).thenReturn(null);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            bukkit.when(Bukkit::getPluginManager).thenReturn(pm);
            bukkit.when(() -> Bukkit.getWorld(WORLD_ID)).thenReturn(world);
            bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
            // The apply path calls Bukkit.createBlockData when it pushes
            // the replacement; return a stub BlockData — the engine
            // doesn't introspect it further on this path.
            BlockData replacementData = mock(BlockData.class);
            bukkit.when(() -> Bukkit.createBlockData(org.mockito.ArgumentMatchers.anyString()))
                    .thenReturn(replacementData);

            CommandSender sender = mock(CommandSender.class);
            RollbackEngine engine = new RollbackEngine();
            List<RollbackResult> results = engine.applyAll(effects, sender);

            assertThat(results).hasSize(total);

            // Force-overwrite: every effect is Applied, including the
            // odd-indexed blocks whose live state diverged. Nothing is
            // Skipped for a "block changed" reason — the engine doesn't
            // conflict-check before writing. Positional alignment is the
            // load-bearing assertion: results[i] <-> effects[i].
            for (int i = 0; i < total; i++) {
                RollbackResult result = results.get(i);
                assertThat(result)
                        .as("effect %d must be Applied (force-overwrite ignores live state)", i)
                        .isInstanceOf(RollbackResult.Applied.class);
                RollbackResult.Applied applied = (RollbackResult.Applied) result;
                assertThat(applied.effect())
                        .as("result %d must carry its original BlockReplace effect", i)
                        .isInstanceOf(RollbackEffect.BlockReplace.class);
                assertThat(((RollbackEffect.BlockReplace) applied.effect()).location().x())
                        .as("result %d must align positionally with its input effect", i)
                        .isEqualTo(i);
            }
        }
    }

    @Test
    void primaryThreadAssertionRefusesBackgroundCall() {
        // The engine intentionally crashes loudly when called off
        // the main server thread — a regression here would
        // silently corrupt world state under concurrent rollback
        // calls.
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(false);

            RollbackEngine engine = new RollbackEngine();
            CommandSender sender = mock(CommandSender.class);
            BlockLocation loc = new BlockLocation(WORLD_ID, "world", 0, 64, 0);
            List<RollbackEffect> oneEffect = List.of(new RollbackEffect.BlockReplace(
                    loc,
                    snapshot(Material.STONE, "minecraft:stone"),
                    snapshot(Material.AIR, "minecraft:air")));

            assertThat(org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalStateException.class,
                    () -> engine.applyAll(oneEffect, sender)).getMessage())
                    .contains("main thread");
        }
    }

    private static BlockSnapshot snapshot(Material material, String blockData) {
        return new BlockSnapshot(material, blockData,
                List.of(), List.of(), List.of(), List.of(), null);
    }
}
