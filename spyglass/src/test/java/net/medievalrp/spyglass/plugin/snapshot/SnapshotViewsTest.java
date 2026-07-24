package net.medievalrp.spyglass.plugin.snapshot;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The version gate that decides whether a server gets the InvUI {@code
 * /sg snapshot} GUI. InvUI 1.49 supports Minecraft 1.x only; the post-1.21
 * "26.x" scheme falls back to the text-only listing. Getting this wrong
 * either crashes 26.x (loading InvUI) or needlessly drops the GUI on 1.21.x,
 * so it is worth pinning - mirrors {@code SalvageViewsTest} exactly, since
 * both gates share the same InvUI 1.49 version ceiling.
 */
class SnapshotViewsTest {

    @Test
    void supportsCurrentAndOlderOneDotXReleases() {
        assertThat(SnapshotViews.invUiSupported("1.21.11-R0.1-SNAPSHOT")).isTrue();
        assertThat(SnapshotViews.invUiSupported("1.21.8-R0.1-SNAPSHOT")).isTrue();
        assertThat(SnapshotViews.invUiSupported("1.20.4-R0.1-SNAPSHOT")).isTrue();
        assertThat(SnapshotViews.invUiSupported("1.16.5-R0.1-SNAPSHOT")).isTrue();
    }

    @Test
    void rejectsThePost1_21Scheme() {
        assertThat(SnapshotViews.invUiSupported("26.1.2-R0.1-SNAPSHOT")).isFalse();
        assertThat(SnapshotViews.invUiSupported("26.2-R0.1-SNAPSHOT")).isFalse();
    }

    @Test
    void rejectsGarbageAndEmptyDefensively() {
        assertThat(SnapshotViews.invUiSupported(null)).isFalse();
        assertThat(SnapshotViews.invUiSupported("")).isFalse();
        assertThat(SnapshotViews.invUiSupported("not-a-version")).isFalse();
    }
}
