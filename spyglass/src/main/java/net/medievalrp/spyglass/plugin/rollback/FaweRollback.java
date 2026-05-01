package net.medievalrp.spyglass.plugin.rollback;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.block.BlockState;
import java.util.List;
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
     * Batch-apply effects in {@code [from, to)} via a single
     * {@link EditSession} for whichever world the first effect resolves
     * to. The caller is responsible for ensuring all effects in the
     * range share that world (the chunk-batched apply path in
     * {@link RollbackEngine} guarantees this — effects are sorted
     * (worldId, chunkX, chunkZ, y) and the per-chunk window naturally
     * stays in one world).
     *
     * <p>Mutates {@code applied} in place: {@code applied[i] = true}
     * iff {@code session.setBlock} returned {@code true} for
     * {@code effects.get(i)}. Indices outside the {@code [from, to)}
     * range are untouched.
     *
     * <p>If FAWE throws during flush, every remaining entry in the
     * range stays {@code false} and the caller's per-block fallback
     * (i.e. {@link RollbackEngine#applySnapshot}) picks them up.
     */
    static void applyRange(List<RollbackEffect.BlockReplace> effects,
                           int from, int to,
                           boolean[] applied) {
        if (from >= to) {
            return;
        }
        World world = resolveWorld(effects.get(from).location());
        if (world == null) {
            return;
        }
        com.sk89q.worldedit.world.World weWorld = BukkitAdapter.adapt(world);
        try (EditSession session = WorldEdit.getInstance().newEditSession(weWorld)) {
            for (int i = from; i < to; i++) {
                RollbackEffect.BlockReplace effect = effects.get(i);
                BlockSnapshot replacement = effect.replacement();
                BlockData data;
                try {
                    data = Bukkit.createBlockData(replacement.blockData());
                } catch (IllegalArgumentException ex) {
                    // Stored blockdata string didn't parse — leave
                    // applied[i] = false so the caller's fallback path
                    // marks it Skipped.
                    continue;
                }
                BlockVector3 pos = BlockVector3.at(
                        effect.location().x(),
                        effect.location().y(),
                        effect.location().z());
                BlockState weState = BukkitAdapter.adapt(data);
                if (session.setBlock(pos, weState)) {
                    applied[i] = true;
                }
            }
        } catch (Throwable thrown) {
            // EditSession.close() flushed and something went wrong
            // mid-batch. Whatever already applied stays applied; the
            // rest fall through to the per-block fallback.
            Bukkit.getLogger().warning("Spyglass FAWE rollback range failed: " + thrown.getMessage());
        }
    }

    private static World resolveWorld(BlockLocation location) {
        World byUuid = Bukkit.getWorld(location.worldId());
        if (byUuid != null) {
            return byUuid;
        }
        return Bukkit.getWorld(location.worldName());
    }

    /**
     * Cheap predicate the engine uses to split effects between the
     * fast palette-only path and the slow tile-entity-bearing path.
     * A snapshot is "simple" when no tile-entity state needs
     * re-application via Bukkit's {@code BlockState.update} — empty
     * containers, empty sign text, empty banner patterns, no jukebox
     * record, no decorated-pot sherds.
     *
     * <p>Backed by {@link BlockSnapshot#simple} which the record's
     * compact constructor computes once at construction. Calling this
     * is a single field load. The earlier inline 6-method-call chain
     * lit up as hot frames in the spark profile during 10M-block
     * rollbacks (signFront / containerItems / bannerPatterns
     * accessors don't reliably inline through six links of record
     * components).
     */
    static boolean isSimple(BlockSnapshot snapshot) {
        return snapshot.simple();
    }

    @SuppressWarnings("unused")
    private static BlockSnapshots referenceForCompiler() {
        return null;
    }
}
