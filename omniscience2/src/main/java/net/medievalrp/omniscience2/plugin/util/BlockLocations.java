package net.medievalrp.omniscience2.plugin.util;

import java.util.Optional;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

public final class BlockLocations {

    private BlockLocations() {
    }

    public static net.medievalrp.omniscience2.api.util.BlockLocation fromLocation(Location location) {
        World world = location.getWorld();
        return new net.medievalrp.omniscience2.api.util.BlockLocation(
                world.getUID(),
                world.getName(),
                location.getBlockX(),
                location.getBlockY(),
                location.getBlockZ());
    }

    public static Optional<World> resolveWorld(net.medievalrp.omniscience2.api.util.BlockLocation location) {
        World world = Bukkit.getWorld(location.worldId());
        if (world != null) {
            return Optional.of(world);
        }
        return Optional.ofNullable(Bukkit.getWorld(location.worldName()));
    }

    public static Location toBlockCenter(net.medievalrp.omniscience2.api.util.BlockLocation location) {
        World world = resolveWorld(location).orElse(null);
        if (world == null) {
            return null;
        }
        return new Location(world, location.x() + 0.5D, location.y(), location.z() + 0.5D);
    }
}
