package net.medievalrp.spyglass.plugin.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import net.medievalrp.spyglass.api.event.StoredItem;
import org.junit.jupiter.api.Test;

/**
 * Codec contract for {@link BsonBlobs#encodeStoredItem} /
 * {@link BsonBlobs#decodeStoredItem} — the path the ClickHouse backend
 * uses to fold a {@link StoredItem} into its {@code item} / {@code
 * before_item} / {@code after_item} blob columns.
 *
 * <p>#103 made forensic-only records (pickups, drops) carry a projection
 * with a {@code null} {@code data} blob. This pins that a null component
 * round-trips through the shared record codec without throwing and stays
 * null — the same {@code RecordCodecProvider} codec the Mongo nested-item
 * path uses, so it guards both backends' tolerance of the projection
 * shape. No Mongo / ClickHouse container required.
 */
class BsonBlobsTest {

    @Test
    void storedItemWithNullDataRoundTripsKeepingProjections() {
        StoredItem projection = new StoredItem(
                0, "DIAMOND_SWORD", null,
                "Excaliblur",
                List.of("Forged in fire", "Blessed by saints"),
                List.of("sharpness=5", "mending=1"));

        StoredItem back = BsonBlobs.decodeStoredItem(BsonBlobs.encodeStoredItem(projection));

        assertThat(back).isNotNull();
        assertThat(back.data()).as("null blob must stay null through the blob codec").isNull();
        assertThat(back.material()).isEqualTo("DIAMOND_SWORD");
        assertThat(back.name()).isEqualTo("Excaliblur");
        assertThat(back.lore()).containsExactly("Forged in fire", "Blessed by saints");
        assertThat(back.enchants()).containsExactly("sharpness=5", "mending=1");
        assertThat(back).isEqualTo(projection);
    }

    @Test
    void storedItemWithNullDataAndNoProjectionsRoundTrips() {
        // The 3-arg shape the ITs use: material only, null data, empty
        // projection lists.
        StoredItem bare = new StoredItem(3, "DIAMOND", null);

        StoredItem back = BsonBlobs.decodeStoredItem(BsonBlobs.encodeStoredItem(bare));

        assertThat(back).isNotNull();
        assertThat(back.slot()).isEqualTo(3);
        assertThat(back.material()).isEqualTo("DIAMOND");
        assertThat(back.data()).isNull();
        assertThat(back.name()).isNull();
        assertThat(back.lore()).isEmpty();
        assertThat(back.enchants()).isEmpty();
    }

    @Test
    void itemWithBlobStillRoundTrips() {
        // Rollbackable/container records keep the blob — make sure the
        // projection change didn't break the populated-data path.
        StoredItem withBlob = new StoredItem(
                1, "IRON_INGOT", "QkFTRTY0", null, List.of(), List.of());

        StoredItem back = BsonBlobs.decodeStoredItem(BsonBlobs.encodeStoredItem(withBlob));

        assertThat(back).isEqualTo(withBlob);
        assertThat(back.data()).isEqualTo("QkFTRTY0");
    }

    @Test
    void nullItemEncodesToNull() {
        assertThat(BsonBlobs.encodeStoredItem(null)).isNull();
        assertThat(BsonBlobs.decodeStoredItem(null)).isNull();
    }
}
