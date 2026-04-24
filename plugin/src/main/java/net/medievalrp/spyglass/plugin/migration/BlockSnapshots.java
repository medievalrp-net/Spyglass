package net.medievalrp.spyglass.plugin.migration;

import java.util.List;
import net.medievalrp.spyglass.api.event.BlockSnapshot;
import org.bukkit.Material;

final class BlockSnapshots {

    private BlockSnapshots() {
    }

    static BlockSnapshot air() {
        return new BlockSnapshot(Material.AIR, "minecraft:air",
                List.of(), List.of(), List.of(), List.of(), null);
    }

    static Material matchMaterial(String name) {
        if (name == null || name.isBlank()) {
            return Material.AIR;
        }
        Material direct = Material.matchMaterial(name, false);
        if (direct != null) {
            return direct;
        }
        Material legacy = Material.matchMaterial(name, true);
        return legacy != null ? legacy : Material.AIR;
    }
}
