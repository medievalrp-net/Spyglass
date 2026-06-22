package net.medievalrp.spyglass.plugin.rollback;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.bukkit.block.data.BlockData;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/**
 * Apply-phase failsafe regression for {@link RollbackEngine} (#127).
 *
 * <p>If a main-thread apply step throws (a malformed container, a chunk
 * resolve failure, a palette write blowing up), the engine must complete
 * its job future <b>exceptionally</b> rather than leave it forever
 * uncompleted. An uncompleted future parks
 * {@code RollbackService.streamPagesAndApply} on {@code fut.join()}
 * indefinitely, so {@code jobQueue.finish()} is never reached and EVERY
 * subsequent rollback / restore / undo job queues forever until the
 * server restarts (plus a leaked physics region and pinned chunk
 * tickets). This pins that an apply-phase throw fails the future instead.
 */
class RollbackEngineFailsafeTest {

    private static final UUID WORLD_ID = UUID.fromString("88888888-8888-8888-8888-888888888888");

    /**
     * Runs every scheduled task inline but <b>swallows</b> any throwable,
     * exactly like the real Bukkit scheduler does with a thrown task. Under
     * this support a thrown apply step that escaped its handler would leave
     * the job future uncompleted -- reproducing the production wedge.
     */
    private static ServiceSupport swallowingSupport() {
        return new ServiceSupport() {
            @Override
            public void onMainThread(Runnable runnable) {
                try {
                    runnable.run();
                } catch (Throwable swallowed) {
                    // Bukkit logs and drops; the engine must not depend on this.
                }
            }

            @Override
            public void onMainThreadLater(long delayTicks, Runnable runnable) {
                onMainThread(runnable);
            }

            @Override
            public void onAsyncThread(Runnable runnable) {
                onMainThread(runnable);
            }
        };
    }

    @Test
    void applyPhaseThrowFailsTheJobFutureInsteadOfWedging() {
        BlockLocation loc = new BlockLocation(WORLD_ID, "world", 5, 64, 9);
        RollbackEffect effect = new RollbackEffect.BlockReplace(loc,
                snapshot(Material.STONE, "minecraft:stone"),
                snapshot(Material.AIR, "minecraft:air"));

        World world = mock(World.class);
        when(world.getUID()).thenReturn(WORLD_ID);
        when(world.getName()).thenReturn("world");

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
            // Inject a deterministic apply-phase failure: the salvage hook
            // fires during the main-thread chunk-resolve step.
            engine.setSalvageHook(new SalvageHook() {
                @Override
                public void begin(String operatorName, UUID rollbackId) {
                }

                @Override
                public void onChunkResolved(World w, int chunkX, int chunkZ) {
                    throw new IllegalStateException("injected resolve failure");
                }

                @Override
                public void onChunkWritten(World w, int chunkX, int chunkZ) {
                }

                @Override
                public void end() {
                }
            });

            CommandSender sender = mock(CommandSender.class);
            CompletableFuture<List<RollbackResult>> done = engine.applyAllChunked(
                    List.of(effect), sender, swallowingSupport(),
                    Integer.MAX_VALUE, new AtomicBoolean(false));

            // The job future MUST settle (exceptionally) -- never an
            // uncompleted future, which is what wedges the queue.
            assertThat(done.isDone())
                    .as("apply-phase throw must complete the job future, not leave it hanging")
                    .isTrue();
            assertThat(done.isCompletedExceptionally())
                    .as("the throw must fail the future so join() throws and the queue advances")
                    .isTrue();

            Throwable cause = org.junit.jupiter.api.Assertions.assertThrows(
                    java.util.concurrent.CompletionException.class, done::join).getCause();
            assertThat(cause).isInstanceOf(IllegalStateException.class);
            assertThat(cause.getMessage()).contains("injected resolve failure");
        }
    }

    private static BlockSnapshot snapshot(Material material, String blockData) {
        return new BlockSnapshot(material, blockData,
                List.of(), List.of(), List.of(), List.of(), null);
    }
}
