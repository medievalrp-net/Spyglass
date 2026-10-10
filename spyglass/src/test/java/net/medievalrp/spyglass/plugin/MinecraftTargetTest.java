package net.medievalrp.spyglass.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class MinecraftTargetTest {
    private static final List<String> MODERN = List.of("26.1.2", "26.2", "26.3");

    // Paper refuses a plugin whose api-version (the build target) is newer
    // than the server, so a build claims its target and the newer ones only.
    @Test
    void buildsSupportTheirTargetAndNewerTargets() {
        int target = MODERN.indexOf(MinecraftTarget.version());
        assertThat(target).isNotNegative();
        assertThat(MinecraftTarget.supported()).isEqualTo(MODERN.subList(target, MODERN.size()));
        for (String older : MODERN.subList(0, target)) {
            assertThat(MinecraftTarget.supports(older)).as(older).isFalse();
        }
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
