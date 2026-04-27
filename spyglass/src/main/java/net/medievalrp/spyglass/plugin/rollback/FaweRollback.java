package net.medievalrp.spyglass.plugin.rollback;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.block.BlockState;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.medievalrp.spyglass.api.event.BlockSnapshot;
import net.medievalrp.spyglass.api.rollback.RollbackEffect;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.plugin.util.BlockSnapshots;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.jetbrains.annotations.ApiStatus;

/**
 * Batch block placement for rollbacks via WorldEdit / FastAsyncWorldEdit.
 * Mirrors v1's {@code FAWERollbackHandler}: group changes by world, open
 * one {@link EditSession} per world, push every block through
 * {@link EditSession#setBlock(BlockVector3, BlockState)}, then close to
 * flush. FAWE handles physics suppression, deferred light, bulk chunk
 * packets, and async chunk writes — yielding ~one tick of work for
 * thousands of simple block changes that would otherwise be thousands
 * of individual {@code Block.setBlockData} calls.
 *
 * <p>"Simple" here means {@code material + blockData} only — no tile
 * entity state. Containers, signs, banners, and jukeboxes still need
 * the per-block path in {@link RollbackEngine#applySnapshot} so their
 * inventory / sign text / patterns / record get re-applied via
 * {@code BlockState.update}. The caller is responsible for that split;
 * this util only handles the simple case.
 *
 * <p>Returns the count successfully placed; the caller emits the
 * matching {@link net.medievalrp.spyglass.api.rollback.RollbackResult}
 * entries.
 */
@ApiStatus.Internal
final class FaweRollback {

    private FaweRollback() {
    }

    /**
     * {@code true} only when WorldEdit (or FAWE, which provides the same
     * API surface) is available. Computed lazily; see comment in
     * {@link #isAvailable()}.
     */
    static boolean isAvailable() {
        if (Bukkit.getPluginManager().getPlugin("FastAsyncWorldEdit") != null) {
            return true;
        }
        return Bukkit.getPluginManager().getPlugin("WorldEdit") != null;
    }

    /**
     * Batch-apply each effect's replacement blockdata via WorldEdit.
     * Returns parallel arrays: {@code applied[i] == true} when the
     * setBlock landed for {@code effects.get(i)}. Skipped effects (world
     * unresolved, blockdata invalid) are reported as {@code false}.
     *
     * <p>If any unrecoverable error happens (FAWE throws during flush),
     * every entry from that point on falls through as {@code false} and
     * the caller's per-block fallback can pick them up.
     */
    static boolean[] applyAll(List<RollbackEffect.BlockReplace> effects) {
        boolean[] applied = new boolean[effects.size()];
        if (effects.isEmpty()) {
            return applied;
        }

        // Group indices by world so each EditSession sees a single world
        // (WorldEdit wants one world per session). Index list preserves
        // input order so the caller's parallel result array stays aligned.
        Map<World, java.util.List<Integer>> indicesByWorld = new HashMap<>();
        for (int i = 0; i < effects.size(); i++) {
            RollbackEffect.BlockReplace effect = effects.get(i);
            World world = resolveWorld(effect.location());
            if (world == null) {
                continue;
            }
            indicesByWorld.computeIfAbsent(world, k -> new java.util.ArrayList<>()).add(i);
        }

        for (Map.Entry<World, java.util.List<Integer>> entry : indicesByWorld.entrySet()) {
            World world = entry.getKey();
            java.util.List<Integer> indices = entry.getValue();
            com.sk89q.worldedit.world.World weWorld = BukkitAdapter.adapt(world);
            try (EditSession session = WorldEdit.getInstance().newEditSession(weWorld)) {
                for (int index : indices) {
                    RollbackEffect.BlockReplace effect = effects.get(index);
                    BlockSnapshot replacement = effect.replacement();
                    BlockData data;
                    try {
                        data = Bukkit.createBlockData(replacement.blockData());
                    } catch (IllegalArgumentException ex) {
                        // Stored blockdata string didn't parse — skip,
                        // caller's fallback will mark it Skipped.
                        continue;
                    }
                    BlockVector3 pos = BlockVector3.at(
                            effect.location().x(),
                            effect.location().y(),
                            effect.location().z());
                    BlockState weState = BukkitAdapter.adapt(data);
                    if (session.setBlock(pos, weState)) {
                        applied[index] = true;
                    }
                }
            } catch (Throwable thrown) {
                // EditSession.close() flushed and something went wrong
                // mid-batch. Whatever already applied stays applied; the
                // rest fall through to the per-block fallback.
                Bukkit.getLogger().warning("Spyglass FAWE rollback batch failed: " + thrown.getMessage());
            }
        }
        return applied;
    }

    private static World resolveWorld(BlockLocation location) {
        World byUuid = Bukkit.getWorld(location.worldId());
        if (byUuid != null) {
            return byUuid;
        }
        return Bukkit.getWorld(location.worldName());
    }

    /**
     * Cheap predicate the engine uses to split effects between the FAWE
     * batch path and the per-block path. A snapshot is "simple" when no
     * tile-entity state needs re-application via Bukkit's
     * {@code BlockState.update}: empty containers, empty sign text,
     * empty banner patterns, no jukebox record. FAWE only writes the
     * {@code material + blockData}, so anything else has to fall through.
     */
    static boolean isSimple(BlockSnapshot snapshot) {
        return snapshot.containerItems().isEmpty()
                && snapshot.signFront().isEmpty()
                && snapshot.signBack().isEmpty()
                && snapshot.bannerPatterns().isEmpty()
                && snapshot.jukeboxRecord() == null;
    }

    @SuppressWarnings("unused")
    private static BlockSnapshots referenceForCompiler() {
        return null;
    }
}
