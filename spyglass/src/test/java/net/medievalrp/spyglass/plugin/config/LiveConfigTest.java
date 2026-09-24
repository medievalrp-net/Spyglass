package net.medievalrp.spyglass.plugin.config;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import net.medievalrp.spyglass.api.util.Duration;
import org.junit.jupiter.api.Test;

class LiveConfigTest {
    @Test void disablingHandBreakLoggingDoesNotDisableWorldEdit() {
        var config = org.mockito.Mockito.mock(SpyglassConfig.class);
        org.mockito.Mockito.when(config.events()).thenReturn(Map.of("break", new SpyglassConfig.EventSettings(false, "broke", null)));
        org.mockito.Mockito.when(config.worldedit()).thenReturn(new SpyglassConfig.WorldEdit(true));
        var record = org.mockito.Mockito.mock(net.medievalrp.spyglass.api.event.JoinRecord.class);
        org.mockito.Mockito.when(record.event()).thenReturn("break");
        org.mockito.Mockito.when(record.origin()).thenReturn(net.medievalrp.spyglass.api.event.Origin.worldEdit());
        assertThat(LiveConfig.recordsEnabled(config, record)).isTrue();
        org.mockito.Mockito.when(record.origin()).thenReturn(net.medievalrp.spyglass.api.event.Origin.player());
        assertThat(LiveConfig.recordsEnabled(config, record)).isFalse();
    }

    @Test void invalidRetentionIsRejectedBeforeLoadingFallbacks(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        var file = dir.resolve("config.conf");
        java.nio.file.Files.writeString(file, "events.break.retention = garbage");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> LiveConfig.validateFile(file))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private SpyglassConfig config(int queue, String retention, Material wand) {
        return new SpyglassConfig(null, new SpyglassConfig.Storage(Duration.parse(retention), queue, 0, false, 10, Duration.parse("10s")),
                null, null, Map.of("break", new SpyglassConfig.EventSettings(true, "broke", null)),
                List.of("login"), new SpyglassConfig.Tool(wand, Duration.parse("26w")), null, null, null, null, null, null);
    }
    @Test void liveSettingsCanChangeTogether() {
        assertThat(LiveConfig.restartRequired(config(100, "1w", Material.REDSTONE_LAMP),
                config(100, "2w", Material.GLOWSTONE))).isEmpty();
    }
    @Test void pipelineChangesRejectTheWholeReload() {
        assertThat(LiveConfig.restartRequired(config(100, "1w", Material.REDSTONE_LAMP),
                config(200, "2w", Material.GLOWSTONE))).containsExactly("storage.queueCapacity");
    }
}
