package net.medievalrp.spyglass.plugin.command.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import net.medievalrp.spyglass.api.event.StoredItem;
import net.medievalrp.spyglass.api.param.ParamParseException;
import net.medievalrp.spyglass.plugin.snapshot.Reconstruction;
import net.medievalrp.spyglass.plugin.snapshot.SnapshotSession;
import net.medievalrp.spyglass.plugin.snapshot.SnapshotSlot;
import org.junit.jupiter.api.Test;

/**
 * Covers the two pure pieces of {@link SnapshotService}: the restricted
 * {@code /sg snapshot} query-string parser (no default injection, a much
 * smaller key set than {@code QueryStringParser}) and the double-chest
 * merge helper. Everything else (target resolution, store reads, the GUI
 * hand-off) touches live Bukkit/store state and is exercised by the bot
 * probe instead.
 */
class SnapshotServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    // ---- parse(): required t: -------------------------------------------

    @Test
    void tIsRequired() {
        assertThatThrownBy(() -> SnapshotService.parse("p:Steve", NOW))
                .isInstanceOf(ParamParseException.class)
                .hasMessageContaining("t:");
    }

    @Test
    void tAloneIsContainerModeWithNoPlayerName() throws Exception {
        SnapshotService.ParsedQuery parsed = SnapshotService.parse("t:1h", NOW);
        assertThat(parsed.playerMode()).isFalse();
        assertThat(parsed.hasTrg()).isFalse();
        assertThat(parsed.asOf()).isEqualTo(NOW.minusSeconds(3600));
    }

    @Test
    void invalidDurationIsRejected() {
        assertThatThrownBy(() -> SnapshotService.parse("t:notaduration", NOW))
                .isInstanceOf(ParamParseException.class);
    }

    @Test
    void duplicateTIsRejected() {
        assertThatThrownBy(() -> SnapshotService.parse("t:1h t:2h", NOW))
                .isInstanceOf(ParamParseException.class)
                .hasMessageContaining("Duplicate");
    }

    // ---- parse(): key rejection ------------------------------------------

    @Test
    void unknownKeyIsRejected() {
        assertThatThrownBy(() -> SnapshotService.parse("t:1h r:50", NOW))
                .isInstanceOf(ParamParseException.class)
                .hasMessageContaining("not a snapshot key: r");
    }

    @Test
    void bareTokenWithNoColonIsRejected() {
        assertThatThrownBy(() -> SnapshotService.parse("t:1h Steve", NOW))
                .isInstanceOf(ParamParseException.class)
                .hasMessageContaining("not a snapshot key: Steve");
    }

    @Test
    void flagsAreRejected() {
        assertThatThrownBy(() -> SnapshotService.parse("t:1h -g", NOW))
                .isInstanceOf(ParamParseException.class);
    }

    @Test
    void noDefaultRadiusOrRangeIsEverInjected() throws Exception {
        // Only what was typed comes back - no r:/t-range machinery like
        // QueryStringParser's default-time or default-radius predicates.
        SnapshotService.ParsedQuery parsed = SnapshotService.parse("t:1h", NOW);
        assertThat(parsed.x()).isNull();
        assertThat(parsed.worldName()).isNull();
    }

    // ---- parse(): trg parsing --------------------------------------------

    @Test
    void trgParsesExactCoordinates() throws Exception {
        SnapshotService.ParsedQuery parsed = SnapshotService.parse("t:1h trg:100,64,-200", NOW);
        assertThat(parsed.hasTrg()).isTrue();
        assertThat(parsed.x()).isEqualTo(100);
        assertThat(parsed.y()).isEqualTo(64);
        assertThat(parsed.z()).isEqualTo(-200);
    }

    @Test
    void trgRejectsNonCoordinateValues() {
        // Unlike search's trg:, snapshot's has no substring-match fallback -
        // it is coordinates or nothing.
        assertThatThrownBy(() -> SnapshotService.parse("t:1h trg:chest", NOW))
                .isInstanceOf(ParamParseException.class)
                .hasMessageContaining("trg:");
    }

    @Test
    void wRequiresTrg() {
        assertThatThrownBy(() -> SnapshotService.parse("t:1h w:world_nether", NOW))
                .isInstanceOf(ParamParseException.class)
                .hasMessageContaining("w:");
    }

    @Test
    void wWithTrgParsesFine() throws Exception {
        SnapshotService.ParsedQuery parsed = SnapshotService.parse("t:1h trg:1,2,3 w:world_nether", NOW);
        assertThat(parsed.worldName()).isEqualTo("world_nether");
    }

    // ---- parse(): p exclusivity -------------------------------------------

    @Test
    void pAcceptsExactlyOneBareName() throws Exception {
        SnapshotService.ParsedQuery parsed = SnapshotService.parse("t:1h p:Steve", NOW);
        assertThat(parsed.playerMode()).isTrue();
        assertThat(parsed.playerName()).isEqualTo("Steve");
    }

    @Test
    void pRejectsCommaSeparatedNames() {
        assertThatThrownBy(() -> SnapshotService.parse("t:1h p:Steve,Alex", NOW))
                .isInstanceOf(ParamParseException.class)
                .hasMessageContaining("p:");
    }

    @Test
    void pRejectsExcludeSyntax() {
        assertThatThrownBy(() -> SnapshotService.parse("t:1h p:!Steve", NOW))
                .isInstanceOf(ParamParseException.class)
                .hasMessageContaining("p:");
    }

    @Test
    void pAndTrgCannotBeCombined() {
        assertThatThrownBy(() -> SnapshotService.parse("t:1h p:Steve trg:1,2,3", NOW))
                .isInstanceOf(ParamParseException.class)
                .hasMessageContaining("p:")
                .hasMessageContaining("trg:");
    }

    @Test
    void duplicatePIsRejected() {
        assertThatThrownBy(() -> SnapshotService.parse("t:1h p:Steve p:Alex", NOW))
                .isInstanceOf(ParamParseException.class)
                .hasMessageContaining("Duplicate");
    }

    // ---- mergeHalves(): double-chest offset logic -------------------------

    private static StoredItem item(int slot, String material) {
        return new StoredItem(slot, material, "blob-" + slot);
    }

    @Test
    void mergeOffsetsRightHalfSlotsByLeftSize() {
        Reconstruction left = new Reconstruction(
                List.of(new SnapshotSlot(0, 0, item(0, "DIAMOND"))),
                SnapshotSession.Certainty.CERTAIN, List.of(), List.of());
        Reconstruction right = new Reconstruction(
                List.of(new SnapshotSlot(0, 0, item(0, "GOLD_INGOT")), new SnapshotSlot(5, 0, item(5, "IRON_INGOT"))),
                SnapshotSession.Certainty.CERTAIN, List.of(), List.of());

        Reconstruction merged = SnapshotService.mergeHalves(left, 27, right);

        assertThat(merged.slots()).hasSize(3);
        assertThat(merged.slots().stream().map(SnapshotSlot::slot)).containsExactlyInAnyOrder(0, 27, 32);
        assertThat(merged.certain()).isTrue();
    }

    @Test
    void mergeCertaintyIsWorstOfBothHalves() {
        Reconstruction certainLeft = new Reconstruction(List.of(), SnapshotSession.Certainty.CERTAIN, List.of(), List.of());
        Reconstruction uncertainRight = new Reconstruction(List.of(), SnapshotSession.Certainty.UNCERTAIN,
                List.of("container no longer present"), List.of());

        Reconstruction merged = SnapshotService.mergeHalves(certainLeft, 27, uncertainRight);

        assertThat(merged.certain()).isFalse();
        assertThat(merged.notes()).containsExactly("right half: container no longer present");
    }

    @Test
    void mergeOffsetsMismatchSlotIndices() {
        Reconstruction.Mismatch rightMismatch =
                new Reconstruction.Mismatch(3, Reconstruction.Mismatch.Kind.END_STATE, "empty", "DIRT");
        Reconstruction left = new Reconstruction(List.of(), SnapshotSession.Certainty.CERTAIN, List.of(), List.of());
        Reconstruction right = new Reconstruction(List.of(), SnapshotSession.Certainty.UNCERTAIN,
                List.of("mismatch"), List.of(rightMismatch));

        Reconstruction merged = SnapshotService.mergeHalves(left, 27, right);

        assertThat(merged.mismatches()).hasSize(1);
        assertThat(merged.mismatches().get(0).slot()).isEqualTo(30);
        assertThat(merged.mismatches().get(0).expected()).isEqualTo("empty");
        assertThat(merged.mismatches().get(0).actual()).isEqualTo("DIRT");
    }

    @Test
    void mergePreservesBothHalvesNotesInOrder() {
        Reconstruction left = new Reconstruction(List.of(), SnapshotSession.Certainty.UNCERTAIN,
                List.of("left note"), List.of());
        Reconstruction right = new Reconstruction(List.of(), SnapshotSession.Certainty.UNCERTAIN,
                List.of("right note"), List.of());

        Reconstruction merged = SnapshotService.mergeHalves(left, 27, right);

        assertThat(merged.notes()).containsExactly("left half: left note", "right half: right note");
    }
}
