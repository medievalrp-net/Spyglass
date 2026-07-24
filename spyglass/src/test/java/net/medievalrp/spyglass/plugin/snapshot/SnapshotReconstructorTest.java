package net.medievalrp.spyglass.plugin.snapshot;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import net.medievalrp.spyglass.api.event.ContainerDepositRecord;
import net.medievalrp.spyglass.api.event.ContainerWithdrawRecord;
import net.medievalrp.spyglass.api.event.EventRecord;
import net.medievalrp.spyglass.api.event.Origin;
import net.medievalrp.spyglass.api.event.Source;
import net.medievalrp.spyglass.api.event.StoredItem;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.api.util.EventIds;
import net.medievalrp.spyglass.plugin.snapshot.Reconstruction.Mismatch;
import net.medievalrp.spyglass.plugin.snapshot.SnapshotSession.Certainty;
import org.junit.jupiter.api.Test;

/**
 * Pure reverse-apply plus forward-replay tests for {@link SnapshotReconstructor}.
 * Records are built with synthetic {@link StoredItem}s whose {@code data} blobs
 * are arbitrary marker strings - the reconstructor compares slots by that blob,
 * so no real serialization (and no Bukkit) is needed. Ids are minted with
 * explicit sequences via {@link EventIds#uuidOf} so same-instant ordering is
 * deterministic.
 */
class SnapshotReconstructorTest {

    private static final UUID WORLD = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PLAYER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final BlockLocation LOCATION = new BlockLocation(WORLD, "world", 10, 64, 20);
    private static final Instant T = Instant.parse("2026-07-24T00:00:00Z");
    private static final int SIZE = 27;

    // --- happy path: a deposit-then-withdraw chain rewinds to the T-state ---

    @Test
    void depositThenWithdrawChainRewindsToExactTState() {
        StoredItem at64 = item("DIAMOND", "diamond#64");
        StoredItem at50 = item("DIAMOND", "diamond#50");
        StoredItem at58 = item("DIAMOND", "diamond#58");

        // T-state slot 0 holds DIAMOND#64. A withdraw drops it to #50, then a
        // deposit tops it up to #58, which is what live shows.
        ContainerWithdrawRecord w = withdraw(1, T.plusSeconds(10), 0, at64, at50);
        ContainerDepositRecord d = deposit(2, T.plusSeconds(20), 0, at50, at58);

        StoredItem[] live = empty();
        live[0] = at58;

        Reconstruction r = SnapshotReconstructor.reconstruct(
                List.<EventRecord>of(w, d), live, SIZE, T, true, false);

        assertThat(r.certainty()).isEqualTo(Certainty.CERTAIN);
        assertThat(r.mismatches()).isEmpty();
        assertThat(r.slots()).singleElement().satisfies(s -> {
            assertThat(s.slot()).isEqualTo(0);
            assertThat(s.item().data()).isEqualTo("diamond#64");
            assertThat(s.count()).isEqualTo(SnapshotReconstructor.COUNT_IN_BLOB);
        });
    }

    // --- interleaved multi-slot chains, out-of-order input ---

    @Test
    void interleavedMultiSlotChainsSortAndReconcileFromShuffledInput() {
        StoredItem x10 = item("IRON_INGOT", "iron#10");
        StoredItem x4 = item("IRON_INGOT", "iron#4");
        StoredItem y5 = item("GOLD_INGOT", "gold#5");
        StoredItem y9 = item("GOLD_INGOT", "gold#9");

        // slot 0: empty -> 10 -> 4. slot 1: 5 -> 9. Interleaved in time.
        ContainerDepositRecord a = deposit(1, T.plusSeconds(10), 0, null, x10);
        ContainerDepositRecord b = deposit(2, T.plusSeconds(20), 1, y5, y9);
        ContainerWithdrawRecord c = withdraw(3, T.plusSeconds(30), 0, x10, x4);

        StoredItem[] live = empty();
        live[0] = x4;
        live[1] = y9;

        // Deliberately shuffled input.
        Reconstruction r = SnapshotReconstructor.reconstruct(
                List.<EventRecord>of(c, a, b), live, SIZE, T, true, false);

        assertThat(r.certainty()).isEqualTo(Certainty.CERTAIN);
        assertThat(r.mismatches()).isEmpty();
        // slot 0 rewinds to empty (dropped from output); slot 1 to gold#5.
        assertThat(r.slots()).singleElement().satisfies(s -> {
            assertThat(s.slot()).isEqualTo(1);
            assertThat(s.item().data()).isEqualTo("gold#5");
        });
    }

    // --- an unexplained live item that no record touches stays, still CERTAIN ---

    @Test
    void unexplainedLiveItemSurvivesAndStaysCertainWhenReplayReconciles() {
        StoredItem stone10 = item("STONE", "stone#10");
        StoredItem stone3 = item("STONE", "stone#3");
        StoredItem heirloom = item("NETHERITE_SWORD", "sword#unique");

        ContainerWithdrawRecord w = withdraw(1, T.plusSeconds(10), 0, stone10, stone3);

        StoredItem[] live = empty();
        live[0] = stone3;
        live[5] = heirloom; // never referenced by any record

        Reconstruction r = SnapshotReconstructor.reconstruct(
                List.<EventRecord>of(w), live, SIZE, T, true, false);

        assertThat(r.certainty()).isEqualTo(Certainty.CERTAIN);
        assertThat(r.mismatches()).isEmpty();
        assertThat(r.slots()).extracting(SnapshotSlot::slot).containsExactly(0, 5);
        assertThat(r.slots()).filteredOn(s -> s.slot() == 5)
                .singleElement()
                .satisfies(s -> assertThat(s.item().data()).isEqualTo("sword#unique"));
        assertThat(r.slots()).filteredOn(s -> s.slot() == 0)
                .singleElement()
                .satisfies(s -> assertThat(s.item().data()).isEqualTo("stone#10"));
    }

    // --- a tampered mid-chain record fails the forward-replay step check ---

    @Test
    void tamperedMidChainRecordIsUncertainWithSlotNamedNote() {
        StoredItem a5 = item("APPLE", "apple#5");
        StoredItem a10 = item("APPLE", "apple#10");
        StoredItem bogus = item("BREAD", "bread#7"); // R2.before lies

        // R1: empty -> 5. R2 claims before=BREAD#7 (tampered) -> 10. Live is 10,
        // so the end state still reconciles; only the mid-chain step is wrong.
        ContainerDepositRecord r1 = deposit(1, T.plusSeconds(10), 0, null, a5);
        ContainerDepositRecord r2 = deposit(2, T.plusSeconds(20), 0, bogus, a10);

        StoredItem[] live = empty();
        live[0] = a10;

        Reconstruction r = SnapshotReconstructor.reconstruct(
                List.<EventRecord>of(r1, r2), live, SIZE, T, true, false);

        assertThat(r.certainty()).isEqualTo(Certainty.UNCERTAIN);
        assertThat(r.mismatches())
                .anySatisfy(m -> {
                    assertThat(m.slot()).isEqualTo(0);
                    assertThat(m.kind()).isEqualTo(Mismatch.Kind.REPLAY_STEP);
                });
        assertThat(r.notes()).anyMatch(n -> n.contains("slot 0"));
    }

    // --- an untracked change to the live container shows as an end-state diff ---

    @Test
    void untrackedLiveChangeIsUncertainEndStateMismatch() {
        StoredItem c10 = item("COBBLESTONE", "cobble#10");
        StoredItem c20 = item("COBBLESTONE", "cobble#20");
        StoredItem tampered = item("COBBLESTONE", "cobble#40"); // live grew past the record

        ContainerDepositRecord d = deposit(1, T.plusSeconds(10), 0, c10, c20);

        StoredItem[] live = empty();
        live[0] = tampered; // records say 20, live has 40

        Reconstruction r = SnapshotReconstructor.reconstruct(
                List.<EventRecord>of(d), live, SIZE, T, true, false);

        assertThat(r.certainty()).isEqualTo(Certainty.UNCERTAIN);
        assertThat(r.mismatches())
                .anySatisfy(m -> {
                    assertThat(m.slot()).isEqualTo(0);
                    assertThat(m.kind()).isEqualTo(Mismatch.Kind.END_STATE);
                });
    }

    // --- a legacy slot < 0 row forces hard UNCERTAIN ---

    @Test
    void legacyNegativeSlotRecordIsHardUncertain() {
        StoredItem full = item("EMERALD", "emerald#64");
        // A clean chain for slot 0 alongside one pre-#268 slot=-1 row.
        ContainerDepositRecord clean = deposit(1, T.plusSeconds(10), 0, null, full);
        ContainerDepositRecord legacy = deposit(2, T.plusSeconds(20), -1, null, full);

        StoredItem[] live = empty();
        live[0] = full;

        Reconstruction r = SnapshotReconstructor.reconstruct(
                List.<EventRecord>of(clean, legacy), live, SIZE, T, true, false);

        assertThat(r.certainty()).isEqualTo(Certainty.UNCERTAIN);
        assertThat(r.notes()).anyMatch(n -> n.contains("predates per-slot logging"));
        // The legacy row is not applied, so the clean slot still reconstructs.
        assertThat(r.slots()).isEmpty(); // slot 0 rewinds to empty (before the deposit)
    }

    // --- an absent container reconstructs from records against empty, UNCERTAIN ---

    @Test
    void absentContainerReconstructsFromRecordsAgainstEmpty() {
        StoredItem kept = item("BOOK", "book#1");
        StoredItem before = item("BOOK", "book#3");

        // A withdraw whose before-state is what stood at T; the block is gone,
        // so live is empty and forward-replay is skipped.
        ContainerWithdrawRecord w = withdraw(1, T.plusSeconds(10), 4, before, kept);

        StoredItem[] live = empty(); // container gone

        Reconstruction r = SnapshotReconstructor.reconstruct(
                List.<EventRecord>of(w), live, SIZE, T, false, false);

        assertThat(r.certainty()).isEqualTo(Certainty.UNCERTAIN);
        assertThat(r.notes()).anyMatch(n -> n.contains("container no longer present"));
        assertThat(r.slots()).singleElement().satisfies(s -> {
            assertThat(s.slot()).isEqualTo(4);
            assertThat(s.item().data()).isEqualTo("book#3");
        });
    }

    // --- a self-mutating block is UNCERTAIN even with a clean chain ---

    @Test
    void selfMutatingBlockIsHardUncertain() {
        StoredItem coal = item("COAL", "coal#8");
        StoredItem coal4 = item("COAL", "coal#4");
        ContainerWithdrawRecord w = withdraw(1, T.plusSeconds(10), 0, coal, coal4);

        StoredItem[] live = empty();
        live[0] = coal4;

        Reconstruction r = SnapshotReconstructor.reconstruct(
                List.<EventRecord>of(w), live, SIZE, T, true, true);

        assertThat(r.certainty()).isEqualTo(Certainty.UNCERTAIN);
        assertThat(r.notes()).anyMatch(n -> n.contains("self-mutating"));
    }

    // --- a SWAP_WITH_CURSOR shape: withdraw + deposit, same instant, same slot ---

    @Test
    void swapWithCursorShapeReplaysCleanlyInBothDirections() {
        StoredItem was = item("SHIELD", "shield#1");   // slot held this at T
        StoredItem now = item("TORCH", "torch#12");    // cursor put this in

        Instant instant = T.plusSeconds(10);
        // The swap is one click: withdraw the old stack (before=SHIELD,
        // after=empty), then deposit the cursor (before=empty, after=TORCH),
        // both at the same instant. Sequence ids (1 then 2) fix the order.
        ContainerWithdrawRecord out = withdraw(1, instant, 0, was, null);
        ContainerDepositRecord in = deposit(2, instant, 0, null, now);

        StoredItem[] live = empty();
        live[0] = now;

        // Pass them reversed to prove ordering comes from (occurred, id), not
        // list order.
        Reconstruction r = SnapshotReconstructor.reconstruct(
                List.<EventRecord>of(in, out), live, SIZE, T, true, false);

        assertThat(r.certainty()).isEqualTo(Certainty.CERTAIN);
        assertThat(r.mismatches()).isEmpty();
        assertThat(r.slots()).singleElement().satisfies(s -> {
            assertThat(s.slot()).isEqualTo(0);
            assertThat(s.item().data()).isEqualTo("shield#1");
        });
    }

    // --- records older than T are ignored (defensive window filter) ---

    @Test
    void recordsBeforeTAreIgnored() {
        StoredItem before = item("STICK", "stick#2");
        StoredItem after = item("STICK", "stick#5");
        // This op happened before T; it must not be reverse-applied.
        ContainerDepositRecord stale = deposit(1, T.minusSeconds(60), 0, before, after);

        StoredItem[] live = empty();
        live[0] = after;

        Reconstruction r = SnapshotReconstructor.reconstruct(
                List.<EventRecord>of(stale), live, SIZE, T, true, false);

        assertThat(r.certainty()).isEqualTo(Certainty.CERTAIN);
        // The stale op was skipped, so the T-state is just the live contents.
        assertThat(r.slots()).singleElement().satisfies(s ->
                assertThat(s.item().data()).isEqualTo("stick#5"));
    }

    // --- helpers ---

    private static StoredItem[] empty() {
        return new StoredItem[SIZE];
    }

    private static StoredItem item(String material, String data) {
        return new StoredItem(0, material, data);
    }

    private static ContainerDepositRecord deposit(long seq, Instant occurred, int slot,
                                                  StoredItem before, StoredItem after) {
        return new ContainerDepositRecord(
                EventIds.uuidOf(seq), "deposit", occurred, occurred.plusSeconds(3600),
                Origin.player(), Source.player(PLAYER, "Tester"),
                LOCATION, "srv", "TARGET", "CHEST", slot, 1, before, after);
    }

    private static ContainerWithdrawRecord withdraw(long seq, Instant occurred, int slot,
                                                    StoredItem before, StoredItem after) {
        return new ContainerWithdrawRecord(
                EventIds.uuidOf(seq), "withdraw", occurred, occurred.plusSeconds(3600),
                Origin.player(), Source.player(PLAYER, "Tester"),
                LOCATION, "srv", "TARGET", "CHEST", slot, 1, before, after);
    }
}
