package net.medievalrp.spyglass.plugin.salvage;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import net.medievalrp.spyglass.api.event.StoredItem;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link InFlightTracker} and the dupe-prevention logic it
 * enables in {@link SalvageGui}.
 *
 * <p>The GUI itself cannot be tested headlessly because it calls into Bukkit
 * (inventory creation, player.openInventory). We isolate the de-dupe logic in
 * {@link InFlightTracker} and test that directly, plus the filtering helpers
 * that {@code showChests} / {@code openItems} apply against a list of
 * {@link SalvageSnapshot}s to verify that the in-flight set correctly suppresses
 * stale DB items.
 */
class InFlightTrackerTest {

    // ---- InFlightTracker unit tests ------------------------------------

    @Test
    void markInFlightReturnsTrueFirstTime() {
        InFlightTracker tracker = new InFlightTracker();
        UUID snapId = UUID.randomUUID();

        assertThat(tracker.markInFlight(snapId, 5)).isTrue();
    }

    @Test
    void markInFlightReturnsFalseWhenAlreadyTracked() {
        InFlightTracker tracker = new InFlightTracker();
        UUID snapId = UUID.randomUUID();

        tracker.markInFlight(snapId, 5);
        // Second call on the same key must be refused - this is the dupe gate.
        assertThat(tracker.markInFlight(snapId, 5)).isFalse();
    }

    @Test
    void differentSlotsAreIndependent() {
        InFlightTracker tracker = new InFlightTracker();
        UUID snapId = UUID.randomUUID();

        tracker.markInFlight(snapId, 5);
        // A different container slot on the same snapshot is a distinct key.
        assertThat(tracker.markInFlight(snapId, 6)).isTrue();
    }

    @Test
    void differentSnapshotsAreIndependent() {
        InFlightTracker tracker = new InFlightTracker();
        UUID snapA = UUID.randomUUID();
        UUID snapB = UUID.randomUUID();

        tracker.markInFlight(snapA, 5);
        // Same slot number on a different snapshot is a distinct key.
        assertThat(tracker.markInFlight(snapB, 5)).isTrue();
    }

    @Test
    void clearAllowsRetake() {
        InFlightTracker tracker = new InFlightTracker();
        UUID snapId = UUID.randomUUID();

        tracker.markInFlight(snapId, 5);
        tracker.clear(snapId, 5);

        // After the async write acked, the slot is available again.
        assertThat(tracker.isInFlight(snapId, 5)).isFalse();
        assertThat(tracker.markInFlight(snapId, 5)).isTrue();
    }

    @Test
    void clearAllRemovesEverySlotOfSnapshot() {
        InFlightTracker tracker = new InFlightTracker();
        UUID snapId = UUID.randomUUID();

        tracker.markInFlight(snapId, 0);
        tracker.markInFlight(snapId, 1);
        tracker.markInFlight(snapId, 27);

        tracker.clearAll(snapId);

        assertThat(tracker.isInFlight(snapId, 0)).isFalse();
        assertThat(tracker.isInFlight(snapId, 1)).isFalse();
        assertThat(tracker.isInFlight(snapId, 27)).isFalse();
    }

    @Test
    void clearAllDoesNotAffectOtherSnapshots() {
        InFlightTracker tracker = new InFlightTracker();
        UUID snapA = UUID.randomUUID();
        UUID snapB = UUID.randomUUID();

        tracker.markInFlight(snapA, 3);
        tracker.markInFlight(snapB, 3);

        tracker.clearAll(snapA);

        // snapB's slot must still be tracked.
        assertThat(tracker.isInFlight(snapA, 3)).isFalse();
        assertThat(tracker.isInFlight(snapB, 3)).isTrue();
    }

    // ---- Snapshot-filtering logic (mirrors showChests / openItems) ------

    /**
     * Simulate the filter that {@code showChests} applies to a DB re-read list:
     * items whose containerSlot is in-flight are stripped; snapshots that become
     * empty are excluded entirely. This is the core dupe-prevention path.
     */
    @Test
    void inFlightSlotsAreFilteredFromReReadSnapshots() {
        InFlightTracker tracker = new InFlightTracker();
        UUID snapId = UUID.randomUUID();
        StoredItem takenItem = new StoredItem(5, "DIAMOND", "data");
        StoredItem otherItem = new StoredItem(10, "EMERALD", "data2");

        SalvageSnapshot snap = snap(snapId, List.of(takenItem, otherItem));

        // Mark slot 5 (the diamond) as in-flight.
        tracker.markInFlight(snapId, 5);

        // Apply the same filter showChests uses.
        List<SalvageSnapshot> visible = applyChestFilter(tracker, List.of(snap));

        assertThat(visible).hasSize(1);
        assertThat(visible.get(0).items()).hasSize(1);
        assertThat(visible.get(0).items().get(0).slot()).isEqualTo(10); // only the emerald
    }

    @Test
    void snapshotWithAllSlotsInFlightIsExcludedFromChestList() {
        InFlightTracker tracker = new InFlightTracker();
        UUID snapId = UUID.randomUUID();
        StoredItem onlyItem = new StoredItem(0, "DIAMOND", "data");

        SalvageSnapshot snap = snap(snapId, List.of(onlyItem));
        tracker.markInFlight(snapId, 0);

        List<SalvageSnapshot> visible = applyChestFilter(tracker, List.of(snap));

        // Snapshot fully in-flight - acts as if the rollback was fully recovered.
        assertThat(visible).isEmpty();
    }

    @Test
    void afterAckClearSnapshotAppearsAgainInReRead() {
        InFlightTracker tracker = new InFlightTracker();
        UUID snapId = UUID.randomUUID();
        StoredItem item = new StoredItem(7, "GOLD_INGOT", "data");

        SalvageSnapshot snap = snap(snapId, List.of(item));
        tracker.markInFlight(snapId, 7);

        // Before ack: filtered out.
        assertThat(applyChestFilter(tracker, List.of(snap))).isEmpty();

        // After ack: visible again (the store write landed, DB now reflects the removal).
        tracker.clear(snapId, 7);
        assertThat(applyChestFilter(tracker, List.of(snap))).hasSize(1);
    }

    @Test
    void takeRefusedWhenSlotAlreadyInFlight() {
        InFlightTracker tracker = new InFlightTracker();
        UUID snapId = UUID.randomUUID();

        // First take marks the slot.
        assertThat(tracker.markInFlight(snapId, 3)).isTrue();

        // Immediate second attempt (e.g. double-click) is refused.
        assertThat(tracker.markInFlight(snapId, 3)).isFalse();
    }

    // ---- helpers -------------------------------------------------------

    private static SalvageSnapshot snap(UUID id, List<StoredItem> items) {
        return new SalvageSnapshot(id, UUID.randomUUID(), UUID.randomUUID(),
                "world", 0, 64, 0, "CHEST", "operator", Instant.EPOCH, items);
    }

    /**
     * Applies the same filter that {@code showChests} applies to a DB result:
     * strips in-flight slots from each snapshot and drops empty snapshots.
     */
    private static List<SalvageSnapshot> applyChestFilter(InFlightTracker tracker,
                                                           List<SalvageSnapshot> snaps) {
        List<SalvageSnapshot> out = new ArrayList<>(snaps.size());
        for (SalvageSnapshot snap : snaps) {
            List<StoredItem> visibleItems = snap.items().stream()
                    .filter(item -> !tracker.isInFlight(snap.id(), item.slot()))
                    .toList();
            if (!visibleItems.isEmpty()) {
                out.add(snap.withItems(visibleItems));
            }
        }
        return out;
    }
}
