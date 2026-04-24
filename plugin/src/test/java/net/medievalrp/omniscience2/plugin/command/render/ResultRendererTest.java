package net.medievalrp.omniscience2.plugin.command.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.medievalrp.omniscience2.api.Omniscience2Api;
import net.medievalrp.omniscience2.api.event.BlockUseRecord;
import net.medievalrp.omniscience2.api.event.Origin;
import net.medievalrp.omniscience2.api.event.Source;
import net.medievalrp.omniscience2.api.extension.DisplayRenderer;
import net.medievalrp.omniscience2.api.query.Flag;
import net.medievalrp.omniscience2.api.util.BlockLocation;
import net.medievalrp.omniscience2.plugin.config.Omniscience2Config;
import org.junit.jupiter.api.Test;

class ResultRendererTest {

    private static final UUID PLAYER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID WORLD_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static BlockUseRecord useRecord() {
        Instant now = Instant.now();
        return new BlockUseRecord(
                UUID.randomUUID(), 1, "sculk",
                now, now.plusSeconds(60),
                Origin.player(),
                Source.player(PLAYER_ID, "Alice"),
                new BlockLocation(WORLD_ID, "world", 10, 64, 20),
                "SCULK_SENSOR");
    }

    private static Omniscience2Config configWithVerb(String event, String verb) {
        Omniscience2Config config = mock(Omniscience2Config.class);
        when(config.pastTense(event)).thenReturn(verb);
        return config;
    }

    @Test
    void fallsBackToDefaultTargetWhenNoRendererRegistered() {
        Omniscience2Api api = mock(Omniscience2Api.class);
        when(api.displayRenderer("sculk")).thenReturn(Optional.empty());
        ResultRenderer renderer = new ResultRenderer(api, configWithVerb("sculk", "triggered"));

        Component rendered = renderer.renderSingle(useRecord(), EnumSet.noneOf(Flag.class));
        String plain = PlainTextComponentSerializer.plainText().serialize(rendered);

        assertThat(plain).contains("SCULK_SENSOR");
    }

    @Test
    void customRendererReplacesTargetAndAppendsHoverLines() {
        Omniscience2Api api = mock(Omniscience2Api.class);
        DisplayRenderer custom = new DisplayRenderer() {
            @Override
            public Component renderTarget(net.medievalrp.omniscience2.api.event.EventRecord record,
                                          Component defaultTarget, EnumSet<Flag> flags) {
                return Component.text("CUSTOM_TARGET");
            }

            @Override
            public List<Component> hoverLines(net.medievalrp.omniscience2.api.event.EventRecord record) {
                return List.of(Component.text("extra-hover-line"));
            }
        };
        when(api.displayRenderer("sculk")).thenReturn(Optional.of(custom));
        ResultRenderer renderer = new ResultRenderer(api, configWithVerb("sculk", "triggered"));

        Component rendered = renderer.renderSingle(useRecord(), EnumSet.noneOf(Flag.class));
        String plain = PlainTextComponentSerializer.plainText().serialize(rendered);

        assertThat(plain).contains("CUSTOM_TARGET").doesNotContain("SCULK_SENSOR");
    }

    @Test
    void extendedFlagAppendsLocationLine() {
        Omniscience2Api api = mock(Omniscience2Api.class);
        when(api.displayRenderer("sculk")).thenReturn(Optional.empty());
        ResultRenderer renderer = new ResultRenderer(api, configWithVerb("sculk", "triggered"));

        Component rendered = renderer.renderSingle(useRecord(), EnumSet.of(Flag.EXTENDED));
        String plain = PlainTextComponentSerializer.plainText().serialize(rendered);

        assertThat(plain).contains("x: 10").contains("y: 64").contains("z: 20").contains("world: world");
    }

    @Test
    void rendererExceptionFallsBackToDefault() {
        Omniscience2Api api = mock(Omniscience2Api.class);
        DisplayRenderer broken = new DisplayRenderer() {
            @Override
            public Component renderTarget(net.medievalrp.omniscience2.api.event.EventRecord record,
                                          Component defaultTarget, EnumSet<Flag> flags) {
                throw new RuntimeException("boom");
            }
        };
        when(api.displayRenderer("sculk")).thenReturn(Optional.of(broken));
        ResultRenderer renderer = new ResultRenderer(api, configWithVerb("sculk", "triggered"));

        Component rendered = renderer.renderSingle(useRecord(), EnumSet.noneOf(Flag.class));
        String plain = PlainTextComponentSerializer.plainText().serialize(rendered);

        // Falls back to the default SCULK_SENSOR target instead of throwing.
        assertThat(plain).contains("SCULK_SENSOR");
    }
}
