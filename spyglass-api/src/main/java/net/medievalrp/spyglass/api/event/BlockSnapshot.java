package net.medievalrp.spyglass.api.event;

import java.util.List;
import org.bukkit.Material;

/**
 * Tile-entity-aware snapshot of one block. Beyond material + blockdata,
 * captures the per-block-type extras Spyglass needs to round-trip on
 * rollback: container contents, sign text, banner patterns, jukebox
 * record, decorated-pot sherds. Block types without extras leave their
 * field empty.
 *
 * <p>{@code potSherds}, when present, is a 4-element list ordered
 * {@code [back, left, right, front]} matching
 * {@link org.bukkit.block.DecoratedPot.Side} declaration order. Each
 * entry is the {@link Material#name()} of the sherd on that side
 * (or {@code "BRICK"} for a blank face). Empty list = the block
 * isn't a decorated pot.
 */
public record BlockSnapshot(
        Material material,
        String blockData,
        List<StoredItem> containerItems,
        List<String> signFront,
        List<String> signBack,
        List<String> bannerPatterns,
        String jukeboxRecord,
        List<String> potSherds) {

    public BlockSnapshot {
        containerItems = List.copyOf(containerItems);
        signFront = List.copyOf(signFront);
        signBack = List.copyOf(signBack);
        bannerPatterns = List.copyOf(bannerPatterns);
        potSherds = List.copyOf(potSherds);
    }

    /**
     * Compatibility constructor for callers that pre-date the
     * {@code potSherds} field (BSON decoders for old records, fixture
     * test data, plugin integrations). Defaults sherds to an empty
     * list — non-pot blocks don't care, and pot blocks recorded
     * before this field existed render with default brick sides.
     */
    public BlockSnapshot(Material material, String blockData,
                         List<StoredItem> containerItems,
                         List<String> signFront, List<String> signBack,
                         List<String> bannerPatterns,
                         String jukeboxRecord) {
        this(material, blockData, containerItems, signFront, signBack,
                bannerPatterns, jukeboxRecord, List.of());
    }

    public boolean isAir() {
        return material == Material.AIR;
    }
}
