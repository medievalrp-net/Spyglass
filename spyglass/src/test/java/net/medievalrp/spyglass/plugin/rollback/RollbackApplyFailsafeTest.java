package net.medievalrp.spyglass.plugin.rollback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import net.medievalrp.spyglass.api.event.BlockSnapshot;
import net.medievalrp.spyglass.api.rollback.RollbackEffect;
import net.medievalrp.spyglass.api.rollback.RollbackResult;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.plugin.command.service.ServiceSupport;
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
 * Fail-safe for the chunked apply path (#127). A throw from a main-thread
 * apply step (chunk resolve / finish / salvage capture / worker write) must
 * fail the {@code done} future instead of leaving it forever uncompleted —
 * otherwise {@code RollbackService.streamPagesAndApply} joins on it forever,
 * stranding the rollback thread, wedging the whole job queue, leaking the
 * physics-blocker region, and pinning chunk tickets until restart.
 */
class RollbackApplyFailsafeTest {

    private static final UUID WORLD_ID = UUID.fromString("88888888-8888-8888-8888-888888888888");

    /**
     * A scheduler that mimics Bukkit's {@code runTask}: it executes the
     * runnable but <b>swallows</b> any exception (logs-and-drops in real
     * Bukkit). This is exactly the behaviour that makes the unguarded engine
     * hang — the throw never reaches the {@code done} future.
     */
    private static ServiceSupport swallowingScheduler() {
        return new ServiceSupport() {
            @Override
            public void onMainThread(Runnable runnable) {
                runSwallowing(runnable);
            }

            @Override
            public void onMainThreadLater(long delayTicks, Runnable runnable) {
                runSwallowing(runnable);
            }

            @Override
            public void onAsyncThread(Runnable runnable) {
                runSwallowing(runnable);
            }

            private void runSwallowing(Runnable runnable) {
                try {
                    runnable.run();
                } catch (Throwable ignored) {
                    // Bukkit's scheduler logs and drops; the future never sees it.
                }
            }
        };
    }

    @Test
    void applyStepThrowFailsTheFutureInsteadOfStrandingTheJoin() {
        List<RollbackEffect> effects = List.of(new RollbackEffect.BlockReplace(
                new BlockLocation(WORLD_ID, "world", 0, 64, 0),
                snapshot(Material.STONE, "minecraft:stone"),
                snapshot(Material.AIR, "minecraft:air")));

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            stubBukkit(bukkit);

            // The salvage hook throws while snapshotting the chunk — one of the
            // unguarded main-thread steps in the resolve phase.
            SalvageHook hook = mock(SalvageHook.class);
            doThrow(new RuntimeException("salvage boom"))
                    .when(hook).onChunkResolved(any(World.class), anyInt(), anyInt());

            RollbackEngine engine = new RollbackEngine();
            engine.setSalvageHook(hook);

            CompletableFuture<List<RollbackResult>> future = engine.applyAllChunked(
                    effects, mock(CommandSender.class), swallowingScheduler(),
                    4000, new AtomicBoolean(false));

            // Without the fail-safe this future never completes (the throw is
            // swallowed by the scheduler) and join() would block forever.
            assertThat(future)
                    .as("apply-step throw must terminate the future, not strand the join")
                    .isCompletedExceptionally();
            assertThatThrownBy(future::join).hasMessageContaining("salvage boom");
        }
    }

    @Test
    void happyPathStillCompletesNormallyUnderASwallowingScheduler() {
        // Guards that wrapping the scheduler doesn't break the success path:
        // no throwing hook, the rollback completes and reports the effect.
        List<RollbackEffect> effects = List.of(new RollbackEffect.BlockReplace(
                new BlockLocation(WORLD_ID, "world", 0, 64, 0),
                snapshot(Material.STONE, "minecraft:stone"),
                snapshot(Material.AIR, "minecraft:air")));

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            stubBukkit(bukkit);

            RollbackEngine engine = new RollbackEngine();
            CompletableFuture<List<RollbackResult>> future = engine.applyAllChunked(
                    effects, mock(CommandSender.class), swallowingScheduler(),
                    4000, new AtomicBoolean(false));

            assertThat(future).isCompletedWithValueMatching(results ->
                    results.size() == 1
                            && results.get(0) instanceof RollbackResult.Applied);
        }
    }

    private static void stubBukkit(MockedStatic<Bukkit> bukkit) {
        World world = mock(World.class);
        when(world.getUID()).thenReturn(WORLD_ID);
        when(world.getName()).thenReturn("world");
        Block block = mock(Block.class);
        when(block.getType()).thenReturn(Material.STONE);
        BlockData blockData = mock(BlockData.class);
        when(blockData.getAsString()).thenReturn("minecraft:stone");
        when(block.getBlockData()).thenReturn(blockData);
        when(world.getBlockAt(0, 64, 0)).thenReturn(block);

        PluginManager pm = mock(PluginManager.class);
        when(pm.getPlugin("FastAsyncWorldEdit")).thenReturn(null);
        when(pm.getPlugin("WorldEdit")).thenReturn(null);

        bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
        bukkit.when(Bukkit::getPluginManager).thenReturn(pm);
        bukkit.when(() -> Bukkit.getWorld(WORLD_ID)).thenReturn(world);
        bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
        bukkit.when(() -> Bukkit.createBlockData(anyString())).thenReturn(mock(BlockData.class));
    }

    private static BlockSnapshot snapshot(Material material, String blockData) {
        return new BlockSnapshot(material, blockData,
                List.of(), List.of(), List.of(), List.of(), null);
    }
}
