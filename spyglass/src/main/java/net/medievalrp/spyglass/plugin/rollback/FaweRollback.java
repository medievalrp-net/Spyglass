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

// Batch block placement via WorldEdit/FAWE. FAWE handles physics
// suppression, deferred lighting, bulk chunk packets, and async
// writes, so we just open an EditSession and push blocks through it.
//
// "Simple" blocks (no tile-entity payload) go through here; the
// caller handles containers/signs/banners/jukeboxes separately
// because those need BlockState.update for the tile state.
@ApiStatus.Internal
final class FaweRollback {

    private FaweRollback() {
    }

    static boolean isAvailable() {
        if (Bukkit.getPluginManager().getPlugin("FastAsyncWorldEdit") != null) {
            return true;
        }
        return Bukkit.getPluginManager().getPlugin("WorldEdit") != null;
    }

    // Applies [from, to) through one EditSession. All effects in the
    // range must share a world (caller's sort guarantees this).
    // Sets applied[i] = true on success; untouched indices are
    // picked up by the caller's per-block fallback.
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
                    // Unparseable; let the caller's fallback handle it.
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
            // Whatever applied before the failure stays applied;
            // the rest falls through to the per-block path.
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

    // True when the snapshot has no tile-entity payload (empty
    // container, no sign text, no banner patterns, no jukebox record,
    // no decorated-pot sherds). Backed by BlockSnapshot.simple which
    // is precomputed in the record constructor.
    static boolean isSimple(BlockSnapshot snapshot) {
        return snapshot.simple();
    }

    @SuppressWarnings("unused")
    private static BlockSnapshots referenceForCompiler() {
        return null;
    }
}
