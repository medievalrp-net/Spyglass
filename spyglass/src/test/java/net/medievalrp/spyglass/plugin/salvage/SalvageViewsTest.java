package net.medievalrp.spyglass.plugin.salvage;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class SalvageViewsTest {
    @Test
    void supportsOnlyTheBundledInvUiReleaseLine() {
        String target = net.medievalrp.spyglass.plugin.MinecraftTarget.version();
        assertThat(SalvageViews.invUiSupported(target)).isTrue();
        assertThat(SalvageViews.invUiSupported(target + "-R0.1-SNAPSHOT")).isTrue();
        for (String version : new String[] {"1.21.8-R0.1-SNAPSHOT", "26.1.2", "26.2", "26.3", "26.4", "26.30", "26.3.1", "", "not-a-version"}) {
            if (!version.equals(target)) {
                assertThat(SalvageViews.invUiSupported(version)).as(version).isFalse();
            }
        }
        assertThat(SalvageViews.invUiSupported(null)).isFalse();
    }
}
