package net.medievalrp.spyglass.plugin.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.BasicConfigurationNode;
import org.spongepowered.configurate.serialize.SerializationException;

class SpyglassConfigTest {

    @Test
    void absentRedactKeyFallsBackToDefaultAuthSet() throws SerializationException {
        BasicConfigurationNode root = BasicConfigurationNode.root();

        assertThat(SpyglassConfig.parseCommandRedact(root))
                .isEqualTo(SpyglassConfig.DEFAULT_COMMAND_REDACT);
    }

    @Test
    void explicitEmptyRedactListIsTheOptOut() throws SerializationException {
        BasicConfigurationNode root = BasicConfigurationNode.root();
        root.node("events", "command", "redact").setList(String.class, List.of());

        assertThat(SpyglassConfig.parseCommandRedact(root)).isEmpty();
    }

    @Test
    void explicitRedactListIsUsedVerbatim() throws SerializationException {
        BasicConfigurationNode root = BasicConfigurationNode.root();
        root.node("events", "command", "redact").setList(String.class, List.of("pin", "vault"));

        assertThat(SpyglassConfig.parseCommandRedact(root))
                .containsExactly("pin", "vault");
    }

    @Test
    void nullListElementsAreDroppedInsteadOfFailingLoad() throws SerializationException {
        BasicConfigurationNode root = BasicConfigurationNode.root();
        root.node("events", "command", "redact").set(Arrays.asList("login", null));

        assertThat(SpyglassConfig.parseCommandRedact(root))
                .containsExactly("login");
    }

    @Test
    void absentMetricsKeyDefaultsToEnabled() {
        BasicConfigurationNode root = BasicConfigurationNode.root();

        assertThat(SpyglassConfig.parseMetrics(root).enabled()).isTrue();
    }

    @Test
    void explicitMetricsFalseIsTheOptOut() throws SerializationException {
        BasicConfigurationNode root = BasicConfigurationNode.root();
        root.node("metrics", "enabled").set(false);

        assertThat(SpyglassConfig.parseMetrics(root).enabled()).isFalse();
    }

    @Test
    void explicitMetricsTrueKeepsItEnabled() throws SerializationException {
        BasicConfigurationNode root = BasicConfigurationNode.root();
        root.node("metrics", "enabled").set(true);

        assertThat(SpyglassConfig.parseMetrics(root).enabled()).isTrue();
    }
}
