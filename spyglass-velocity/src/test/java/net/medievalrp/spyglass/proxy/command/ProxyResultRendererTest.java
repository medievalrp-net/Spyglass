package net.medievalrp.spyglass.proxy.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.EnumSet;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.medievalrp.spyglass.api.event.JoinRecord;
import net.medievalrp.spyglass.api.event.Origin;
import net.medievalrp.spyglass.api.event.Source;
import net.medievalrp.spyglass.api.query.Flag;
import net.medievalrp.spyglass.api.util.BlockLocation;
import org.junit.jupiter.api.Test;

class ProxyResultRendererTest {

    private static final UUID PLAYER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID WORLD_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private final ProxyResultRenderer renderer = new ProxyResultRenderer();

    private static JoinRecord joinRecord() {
        Instant now = Instant.now();
        return new JoinRecord(
                UUID.randomUUID(), "join",
                now, now.plusSeconds(60),
                Origin.player(),
                Source.player(PLAYER_ID, "Alice"),
                new BlockLocation(WORLD_ID, "world", 10, 64, 20),
                "survival",
                "Alice",
                "203.0.113.7");
    }

    private static String hoverPlain(Component rendered) {
        net.kyori.adventure.text.event.HoverEvent<?> hover = rendered.hoverEvent();
        assertThat(hover).isNotNull();
        return PlainTextComponentSerializer.plainText()
                .serialize((Component) hover.value());
    }

    @Test
    void joinIpRendersForViewerWithIpPermission() {
        Component rendered = renderer.renderSingle(joinRecord(), EnumSet.noneOf(Flag.class), true);

        assertThat(PlainTextComponentSerializer.plainText().serialize(rendered))
                .contains("203.0.113.7");
        assertThat(hoverPlain(rendered)).contains("IP: 203.0.113.7");
    }

    @Test
    void joinIpMasksInLineAndHoverWithoutIpPermission() {
        Component rendered = renderer.renderSingle(joinRecord(), EnumSet.noneOf(Flag.class), false);

        String plain = PlainTextComponentSerializer.plainText().serialize(rendered);
        assertThat(plain)
                .contains(ProxyResultRenderer.IP_HIDDEN)
                .doesNotContain("203.0.113.7");
        assertThat(hoverPlain(rendered))
                .contains("IP: " + ProxyResultRenderer.IP_HIDDEN)
                .doesNotContain("203.0.113.7");
    }
}
