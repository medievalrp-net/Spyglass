package net.medievalrp.spyglass.plugin.migration;

import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public interface WorldNameLookup {

    String nameFor(UUID worldId);

    static WorldNameLookup bukkit() {
        return uuid -> {
            if (uuid == null) {
                return null;
            }
            World world = Bukkit.getWorld(uuid);
            return world == null ? uuid.toString() : world.getName();
        };
    }

    static WorldNameLookup usingUuid() {
        return uuid -> uuid == null ? null : uuid.toString();
    }
}
