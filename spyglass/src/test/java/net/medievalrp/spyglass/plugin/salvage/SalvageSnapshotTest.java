package net.medievalrp.spyglass.plugin.salvage;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import net.medievalrp.spyglass.api.event.StoredItem;
import org.junit.jupiter.api.Test;

class SalvageSnapshotTest {

    @Test
    void withItemsKeepsMetadataAndSwapsItems() {
        UUID id = UUID.randomUUID();
        SalvageSnapshot snap = new SalvageSnapshot(id, null, UUID.randomUUID(), "world",
                1, 2, 3, "CHEST", "alice", Instant.EPOCH,
                List.of(new StoredItem(0, "DIAMOND", "d")));

        SalvageSnapshot updated = snap.withItems(List.of());

        assertThat(updated.id()).isEqualTo(id);
        assertThat(updated.x()).isEqualTo(1);
        assertThat(updated.y()).isEqualTo(2);
        assertThat(updated.z()).isEqualTo(3);
        assertThat(updated.containerType()).isEqualTo("CHEST");
        assertThat(updated.operatorName()).isEqualTo("alice");
        assertThat(updated.items()).isEmpty();
        // the original is untouched (records are immutable)
        assertThat(snap.items()).hasSize(1);
    }

    @Test
    void nullItemsBecomeEmpty() {
        SalvageSnapshot snap = new SalvageSnapshot(UUID.randomUUID(), null, UUID.randomUUID(),
                "world", 0, 0, 0, "BARREL", "op", Instant.EPOCH, null);
        assertThat(snap.items()).isEmpty();
    }
}
