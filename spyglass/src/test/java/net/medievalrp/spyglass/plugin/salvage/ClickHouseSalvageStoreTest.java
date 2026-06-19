package net.medievalrp.spyglass.plugin.salvage;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import net.medievalrp.spyglass.api.event.StoredItem;
import org.junit.jupiter.api.Test;

/**
 * The ClickHouse salvage store serializes a container's items into one
 * {@code |}-joined string column. These pin that round-trip — the custom bit
 * that the headless tests can reach without a live ClickHouse.
 */
class ClickHouseSalvageStoreTest {

    @Test
    void itemsRoundTripThroughTheBlobColumn() {
        List<StoredItem> items = List.of(
                new StoredItem(0, "DIAMOND", "ZGF0YS1kaWFtb25k", "Shiny", List.of("lore line"), List.of("ench")),
                new StoredItem(5, "EMERALD", "ZGF0YS1lbWVyYWxk"));

        String blob = ClickHouseSalvageStore.encodeItems(items);
        assertThat(blob).contains("|"); // two blobs joined

        List<StoredItem> back = ClickHouseSalvageStore.decodeItems(blob);
        assertThat(back).hasSize(2);
        assertThat(back.get(0).slot()).isEqualTo(0);
        assertThat(back.get(0).material()).isEqualTo("DIAMOND");
        assertThat(back.get(0).data()).isEqualTo("ZGF0YS1kaWFtb25k");
        assertThat(back.get(1).slot()).isEqualTo(5);
        assertThat(back.get(1).material()).isEqualTo("EMERALD");
    }

    @Test
    void emptyAndNullDecodeToEmpty() {
        assertThat(ClickHouseSalvageStore.encodeItems(List.of())).isEmpty();
        assertThat(ClickHouseSalvageStore.decodeItems("")).isEmpty();
        assertThat(ClickHouseSalvageStore.decodeItems(null)).isEmpty();
    }
}
