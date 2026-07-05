package net.medievalrp.spyglass.plugin.salvage;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The version gate that decides whether a server gets the InvUI salvage GUI.
 * InvUI 1.49 supports Minecraft 1.x only; the post-1.21 "26.x" scheme falls back
 * to the command path. Getting this wrong either crashes 26.x (loading InvUI) or
 * needlessly drops the GUI on 1.21.x, so it is worth pinning.
 */
class SalvageViewsTest {

    @Test
    void supportsCurrentAndOlderOneDotXReleases() {
        assertThat(SalvageViews.invUiSupported("1.21.11-R0.1-SNAPSHOT")).isTrue();
        assertThat(SalvageViews.invUiSupported("1.21.8-R0.1-SNAPSHOT")).isTrue();
        assertThat(SalvageViews.invUiSupported("1.20.4-R0.1-SNAPSHOT")).isTrue();
        assertThat(SalvageViews.invUiSupported("1.16.5-R0.1-SNAPSHOT")).isTrue();
    }

    @Test
    void rejectsThePost1_21Scheme() {
        assertThat(SalvageViews.invUiSupported("26.1.2-R0.1-SNAPSHOT")).isFalse();
        assertThat(SalvageViews.invUiSupported("26.2-R0.1-SNAPSHOT")).isFalse();
    }

    @Test
    void rejectsGarbageAndEmptyDefensively() {
        assertThat(SalvageViews.invUiSupported(null)).isFalse();
        assertThat(SalvageViews.invUiSupported("")).isFalse();
        assertThat(SalvageViews.invUiSupported("not-a-version")).isFalse();
    }
}
