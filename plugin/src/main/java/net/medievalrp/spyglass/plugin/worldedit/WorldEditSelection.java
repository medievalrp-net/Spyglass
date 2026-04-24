package net.medievalrp.spyglass.plugin.worldedit;

import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.Region;
import java.util.UUID;
import net.medievalrp.spyglass.api.util.BlockLocation;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

public final class WorldEditSelection {

    private WorldEditSelection() {
    }

    @Nullable
    public static Box currentBox(Player player) {
        try {
            LocalSession session = WorldEdit.getInstance()
                    .getSessionManager()
                    .getIfPresent(BukkitAdapter.adapt(player));
            if (session == null) {
                return null;
            }
            com.sk89q.worldedit.world.World world = session.getSelectionWorld();
            if (world == null) {
                return null;
            }
            Region region;
            try {
                region = session.getSelection(world);
            } catch (Exception ex) {
                return null;
            }
            if (region == null) {
                return null;
            }
            BlockVector3 min = region.getMinimumPoint();
            BlockVector3 max = region.getMaximumPoint();
            UUID worldId = BukkitAdapter.adapt(world).getUID();
            String worldName = BukkitAdapter.adapt(world).getName();
            return new Box(worldId, worldName,
                    new BlockLocation(worldId, worldName, min.x(), min.y(), min.z()),
                    new BlockLocation(worldId, worldName, max.x(), max.y(), max.z()));
        } catch (Throwable thrown) {
            return null;
        }
    }

    public record Box(UUID worldId, String worldName, BlockLocation min, BlockLocation max) {
    }
}
