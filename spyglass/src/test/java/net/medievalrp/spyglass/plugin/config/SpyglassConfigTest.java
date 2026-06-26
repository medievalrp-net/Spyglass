package net.medievalrp.spyglass.plugin.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.BasicConfigurationNode;
import org.spongepowered.configurate.serialize.SerializationException;

class SpyglassConfigTest {

    private static final java.util.logging.Logger LOG =
            java.util.logging.Logger.getLogger("test");

    @Test
    void absentPerEventRetentionInheritsTheGlobal() {
        assertThat(SpyglassConfig.parseEventRetention(null, "break", LOG)).isNull();
        assertThat(SpyglassConfig.parseEventRetention("", "break", LOG)).isNull();
    }

    @Test
    void perEventRetentionParsesDurations() {
        assertThat(SpyglassConfig.parseEventRetention("3d", "say", LOG))
                .isEqualTo(3L * 24 * 60 * 60);
        assertThat(SpyglassConfig.parseEventRetention("12w", "break", LOG))
                .isEqualTo(12L * 7 * 24 * 60 * 60);
    }

    @Test
    void keepForeverKeywordsMapToNever() {
        Long never = net.medievalrp.spyglass.plugin.storage.RetentionPolicy.NEVER_SECONDS;
        assertThat(SpyglassConfig.parseEventRetention("0", "command", LOG)).isEqualTo(never);
        assertThat(SpyglassConfig.parseEventRetention("never", "command", LOG)).isEqualTo(never);
        assertThat(SpyglassConfig.parseEventRetention("forever", "command", LOG)).isEqualTo(never);
    }

    @Test
    void invalidPerEventRetentionFallsBackToTheGlobal() {
        // Unparseable -> warn + null (inherit), never a crash.
        assertThat(SpyglassConfig.parseEventRetention("banana", "say", LOG)).isNull();
    }

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

    // #168: analytics is opt-in (default off), with a 60s default interval
    // floored at 5s.
    @Test
    void absentAnalyticsDefaultsToDisabledWith60sInterval() {
        BasicConfigurationNode root = BasicConfigurationNode.root();
        SpyglassConfig.Analytics analytics = SpyglassConfig.parseAnalytics(root);
        assertThat(analytics.enabled()).isFalse();
        assertThat(analytics.interval().seconds()).isEqualTo(60L);
    }

    @Test
    void explicitAnalyticsEnabledWithCustomInterval() throws SerializationException {
        BasicConfigurationNode root = BasicConfigurationNode.root();
        root.node("analytics", "enabled").set(true);
        root.node("analytics", "interval").set("30s");
        SpyglassConfig.Analytics analytics = SpyglassConfig.parseAnalytics(root);
        assertThat(analytics.enabled()).isTrue();
        assertThat(analytics.interval().seconds()).isEqualTo(30L);
    }

    @Test
    void tinyAnalyticsIntervalIsFlooredAtFiveSeconds() throws SerializationException {
        BasicConfigurationNode root = BasicConfigurationNode.root();
        root.node("analytics", "enabled").set(true);
        root.node("analytics", "interval").set("1s");
        assertThat(SpyglassConfig.parseAnalytics(root).interval().seconds()).isEqualTo(5L);
    }

    @Test
    void malformedAnalyticsIntervalFallsBackToSixtySeconds() throws SerializationException {
        BasicConfigurationNode root = BasicConfigurationNode.root();
        root.node("analytics", "enabled").set(true);
        root.node("analytics", "interval").set("nonsense");
        // A typo in the interval must degrade to the 60s default, not abort the
        // whole config load.
        assertThat(SpyglassConfig.parseAnalytics(root).interval().seconds()).isEqualTo(60L);
    }
}
