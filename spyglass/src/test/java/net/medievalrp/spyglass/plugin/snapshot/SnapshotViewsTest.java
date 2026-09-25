package net.medievalrp.spyglass.plugin.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class SnapshotViewsTest {
    @Test
    void supportsOnlyTheBundledInvUiReleaseLine() {
        String target = net.medievalrp.spyglass.plugin.MinecraftTarget.version();
        assertThat(SnapshotViews.invUiSupported(target)).isTrue();
        assertThat(SnapshotViews.invUiSupported(target + "-R0.1-SNAPSHOT")).isTrue();
        for (String version : new String[] {"1.21.8-R0.1-SNAPSHOT", "26.1.2", "26.2", "26.3", "26.4", "26.30", "26.3.1", "", "not-a-version"}) {
            if (!version.equals(target)) {
                assertThat(SnapshotViews.invUiSupported(version)).as(version).isFalse();
            }
        }
        assertThat(SnapshotViews.invUiSupported(null)).isFalse();
    }
}
