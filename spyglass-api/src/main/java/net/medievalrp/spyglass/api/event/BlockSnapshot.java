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
 *
 * <p>The {@link #simple} component is a derived cache: it's
 * {@code true} iff every tile-entity-payload list is empty AND
 * {@code jukeboxRecord} is null. The compact constructor recomputes
 * it from the other components on every construction so it's always
 * authoritative regardless of what callers pass. The point of caching
 * it is so the hot rollback loop ({@code FaweRollback.isSimple} on
 * worker, dispatch decisions on main) can short-circuit on a single
 * field load instead of chaining six list-empty / null-check method
 * calls. At 10M-block rollback scale that chain showed up on the
 * spark profile (signFront / containerItems / bannerPatterns
 * accessors as hot non-inlined frames) — the engineer's flag.
 */
public record BlockSnapshot(
        Material material,
        String blockData,
        List<StoredItem> containerItems,
        List<String> signFront,
        List<String> signBack,
        List<String> bannerPatterns,
        String jukeboxRecord,
        List<String> potSherds,
        boolean simple) {

    public BlockSnapshot {
        containerItems = List.copyOf(containerItems);
        signFront = List.copyOf(signFront);
        signBack = List.copyOf(signBack);
        bannerPatterns = List.copyOf(bannerPatterns);
        potSherds = List.copyOf(potSherds);
        // Always recompute. Callers passing a value (or BSON decoders
        // defaulting to false on missing field) are ignored — this
        // field is a derived view of the others.
        simple = containerItems.isEmpty()
                && signFront.isEmpty()
                && signBack.isEmpty()
                && bannerPatterns.isEmpty()
                && jukeboxRecord == null
                && potSherds.isEmpty();
    }

    /**
     * Compatibility constructor for the 8-arg shape (post-{@code
     * potSherds}, pre-{@code simple}). The canonical constructor
     * recomputes {@code simple}, so the placeholder {@code false}
     * passed here is never observed.
     */
    public BlockSnapshot(Material material, String blockData,
                         List<StoredItem> containerItems,
                         List<String> signFront, List<String> signBack,
                         List<String> bannerPatterns,
                         String jukeboxRecord,
                         List<String> potSherds) {
        this(material, blockData, containerItems, signFront, signBack,
                bannerPatterns, jukeboxRecord, potSherds, false);
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
                bannerPatterns, jukeboxRecord, List.of(), false);
    }

    public boolean isAir() {
        return material == Material.AIR;
    }
}
