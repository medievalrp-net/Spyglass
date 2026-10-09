package net.medievalrp.spyglass.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MinecraftTargetTest {
    @Test
    void everyBuildSupportsEveryModernTarget() {
        assertThat(MinecraftTarget.supported()).containsExactly("26.1.2", "26.2", "26.3");
        assertThat(MinecraftTarget.supported()).contains(MinecraftTarget.version());
    }

    @Test
    void supportsListedReleasesAndTheirPaperSuffixes() {
        for (String version : MinecraftTarget.supported()) {
            assertThat(MinecraftTarget.supports(version)).as(version).isTrue();
            assertThat(MinecraftTarget.supports(version + "-R0.1-SNAPSHOT")).as(version).isTrue();
        }
        for (String version : new String[] {"1.21.8-R0.1-SNAPSHOT", "26.1", "26.1.1", "26.4", "26.30",
                "26.3.1", "26.2.1", "", "not-a-version"}) {
            assertThat(MinecraftTarget.supports(version)).as(version).isFalse();
        }
        assertThat(MinecraftTarget.supports(null)).isFalse();
    }
}
