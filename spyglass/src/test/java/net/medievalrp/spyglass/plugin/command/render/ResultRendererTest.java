package net.medievalrp.spyglass.plugin.command.render;

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
import net.medievalrp.spyglass.api.SpyglassApi;
import net.medievalrp.spyglass.api.event.BlockUseRecord;
import net.medievalrp.spyglass.api.event.Origin;
import net.medievalrp.spyglass.api.event.Source;
import net.medievalrp.spyglass.api.extension.DisplayRenderer;
import net.medievalrp.spyglass.api.query.Flag;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.plugin.config.SpyglassConfig;
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

    private static SpyglassConfig configWithVerb(String event, String verb) {
        SpyglassConfig config = mock(SpyglassConfig.class);
        when(config.pastTense(event)).thenReturn(verb);
        return config;
    }

    @Test
    void fallsBackToDefaultTargetWhenNoRendererRegistered() {
        SpyglassApi api = mock(SpyglassApi.class);
        when(api.displayRenderer("sculk")).thenReturn(Optional.empty());
        ResultRenderer renderer = new ResultRenderer(api, configWithVerb("sculk", "triggered"));

        Component rendered = renderer.renderSingle(useRecord(), EnumSet.noneOf(Flag.class));
        String plain = PlainTextComponentSerializer.plainText().serialize(rendered);

        assertThat(plain).contains("SCULK_SENSOR");
    }

    @Test
    void customRendererReplacesTargetAndAppendsHoverLines() {
        SpyglassApi api = mock(SpyglassApi.class);
        DisplayRenderer custom = new DisplayRenderer() {
            @Override
            public Component renderTarget(net.medievalrp.spyglass.api.event.EventRecord record,
                                          Component defaultTarget, EnumSet<Flag> flags) {
                return Component.text("CUSTOM_TARGET");
            }

            @Override
            public List<Component> hoverLines(net.medievalrp.spyglass.api.event.EventRecord record) {
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
        SpyglassApi api = mock(SpyglassApi.class);
        when(api.displayRenderer("sculk")).thenReturn(Optional.empty());
        ResultRenderer renderer = new ResultRenderer(api, configWithVerb("sculk", "triggered"));

        Component rendered = renderer.renderSingle(useRecord(), EnumSet.of(Flag.EXTENDED));
        String plain = PlainTextComponentSerializer.plainText().serialize(rendered);

        assertThat(plain).contains("x: 10").contains("y: 64").contains("z: 20").contains("world: world");
    }

    @Test
    void singleRecordCarriesTeleportClickEvent() {
        SpyglassApi api = mock(SpyglassApi.class);
        when(api.displayRenderer("sculk")).thenReturn(Optional.empty());
        ResultRenderer renderer = new ResultRenderer(api, configWithVerb("sculk", "triggered"));

        Component rendered = renderer.renderSingle(useRecord(), EnumSet.noneOf(Flag.class));

        assertThat(findRunCommand(rendered))
                .as("click event on a single result should be /sg tele")
                .startsWith("/sg tele " + WORLD_ID);
    }

    private static String findRunCommand(Component component) {
        net.kyori.adventure.text.event.ClickEvent click = component.clickEvent();
        if (click != null
                && click.action() == net.kyori.adventure.text.event.ClickEvent.Action.RUN_COMMAND) {
            return click.value();
        }
        for (Component child : component.children()) {
            String nested = findRunCommand(child);
            if (nested != null) {
                return nested;
            }
        }
        return null;
    }

    @Test
    void rendererExceptionFallsBackToDefault() {
        SpyglassApi api = mock(SpyglassApi.class);
        DisplayRenderer broken = new DisplayRenderer() {
            @Override
            public Component renderTarget(net.medievalrp.spyglass.api.event.EventRecord record,
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
