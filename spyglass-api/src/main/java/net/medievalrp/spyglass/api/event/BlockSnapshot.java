package net.medievalrp.spyglass.api.event;

import java.util.List;
import org.bukkit.Material;

public record BlockSnapshot(
        Material material,
        String blockData,
        List<StoredItem> containerItems,
        List<String> signFront,
        List<String> signBack,
        List<String> bannerPatterns,
        String jukeboxRecord) {

    public BlockSnapshot {
        containerItems = List.copyOf(containerItems);
        signFront = List.copyOf(signFront);
        signBack = List.copyOf(signBack);
        bannerPatterns = List.copyOf(bannerPatterns);
    }

    public boolean isAir() {
        return material == Material.AIR;
    }
}
