package net.medievalrp.spyglass.plugin.worldedit;

import com.fastasyncworldedit.core.extent.processor.ExtentBatchProcessorHolder;
import com.sk89q.worldedit.event.extent.EditSessionEvent;
import com.sk89q.worldedit.extent.AbstractDelegateExtent;
import com.sk89q.worldedit.extent.Extent;
import java.util.UUID;
import net.medievalrp.spyglass.plugin.listener.ExtractorSupport;
import net.medievalrp.spyglass.plugin.pipeline.Recorder;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
final class FaweHook {

    private static final int MAX_DEPTH = 16;

    private FaweHook() {
    }

    static boolean tryInstall(Recorder recorder, ExtractorSupport support,
                              EditSessionEvent event, Player player, World world) {
        Extent extent = event.getExtent();
        if (extent == null) {
            return false;
        }
        ExtentBatchProcessorHolder holder = findHolder(extent);
        if (holder == null) {
            return false;
        }
        UUID worldId = world.getUID();
        String worldName = world.getName();
        FaweBatchLogger logger = new FaweBatchLogger(
                recorder, support,
                player.getUniqueId(), player.getName(),
                worldId, worldName);
        try {
            holder.addProcessor(logger);
            return true;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private static ExtentBatchProcessorHolder findHolder(Extent start) {
        Extent current = start;
        for (int depth = 0; depth < MAX_DEPTH; depth++) {
            if (current == null) {
                return null;
            }
            if (current instanceof ExtentBatchProcessorHolder holder) {
                return holder;
            }
            if (current instanceof AbstractDelegateExtent delegate) {
                current = delegate.getExtent();
                continue;
            }
            return null;
        }
        return null;
    }
}
