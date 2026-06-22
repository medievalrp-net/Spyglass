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
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.medievalrp.spyglass.api.SpyglassApi;
import net.medievalrp.spyglass.api.event.BlockUseRecord;
import net.medievalrp.spyglass.api.event.ChatRecord;
import net.medievalrp.spyglass.api.event.EntityDeathRecord;
import net.medievalrp.spyglass.api.event.EntityHitRecord;
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
                UUID.randomUUID(), "sculk",
                now, now.plusSeconds(60),
                Origin.player(),
                Source.player(PLAYER_ID, "Alice"),
                new BlockLocation(WORLD_ID, "world", 10, 64, 20),
                "test",
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

        Component rendered = renderer.renderSingle(useRecord(), EnumSet.noneOf(Flag.class), true);
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

        Component rendered = renderer.renderSingle(useRecord(), EnumSet.noneOf(Flag.class), true);
        String plain = PlainTextComponentSerializer.plainText().serialize(rendered);

        assertThat(plain).contains("CUSTOM_TARGET").doesNotContain("SCULK_SENSOR");
    }

    @Test
    void leadingTagsRenderBeforeTheActorName() {
        SpyglassApi api = mock(SpyglassApi.class);
        DisplayRenderer custom = new DisplayRenderer() {
            @Override
            public List<Component> leadingTags(net.medievalrp.spyglass.api.event.EventRecord record) {
                return List.of(Component.text("[OOC]"));
            }
        };
        when(api.displayRenderer("sculk")).thenReturn(Optional.of(custom));
        ResultRenderer renderer = new ResultRenderer(api, configWithVerb("sculk", "triggered"));

        Component rendered = renderer.renderSingle(useRecord(), EnumSet.noneOf(Flag.class), true);
        String plain = PlainTextComponentSerializer.plainText().serialize(rendered);

        assertThat(plain).contains("[OOC]");
        assertThat(plain.indexOf("[OOC]"))
                .as("leading tag must render before the actor name")
                .isLessThan(plain.indexOf("Alice"));
    }

    @Test
    void throwingLeadingTagsFallsBackToNoTags() {
        SpyglassApi api = mock(SpyglassApi.class);
        DisplayRenderer custom = new DisplayRenderer() {
            @Override
            public List<Component> leadingTags(net.medievalrp.spyglass.api.event.EventRecord record) {
                throw new IllegalStateException("boom");
            }
        };
        when(api.displayRenderer("sculk")).thenReturn(Optional.of(custom));
        ResultRenderer renderer = new ResultRenderer(api, configWithVerb("sculk", "triggered"));

        Component rendered = renderer.renderSingle(useRecord(), EnumSet.noneOf(Flag.class), true);
        String plain = PlainTextComponentSerializer.plainText().serialize(rendered);

        assertThat(plain).contains("Alice").doesNotContain("[OOC]");
    }

    private static ChatRecord chatRecord(String target, String message) {
        Instant now = Instant.now();
        return new ChatRecord(
                UUID.randomUUID(), "say",
                now, now.plusSeconds(60),
                Origin.plugin("WhisperNet"),
                Source.player(PLAYER_ID, "Alice"),
                new BlockLocation(WORLD_ID, "world", 10, 64, 20),
                "test",
                target, message, List.of(), java.util.Map.of());
    }

    @Test
    void chatShowsMessageWithChannelPrefixWhenTargetDiffers() {
        // WhisperNet shape: channel parked in target, no DisplayRenderer registered.
        SpyglassApi api = mock(SpyglassApi.class);
        when(api.displayRenderer("say")).thenReturn(Optional.empty());
        ResultRenderer renderer = new ResultRenderer(api, configWithVerb("say", "said"));

        String plain = PlainTextComponentSerializer.plainText().serialize(
                renderer.renderSingle(chatRecord("#OOC", "hi"), EnumSet.noneOf(Flag.class), true));

        assertThat(plain).contains("said #OOC: hi");
    }

    @Test
    void vanillaChatShowsJustTheMessage() {
        // Vanilla shape: target == message (the aggregation key) -> no "hi: hi".
        SpyglassApi api = mock(SpyglassApi.class);
        when(api.displayRenderer("say")).thenReturn(Optional.empty());
        ResultRenderer renderer = new ResultRenderer(api, configWithVerb("say", "said"));

        String plain = PlainTextComponentSerializer.plainText().serialize(
                renderer.renderSingle(chatRecord("hi", "hi"), EnumSet.noneOf(Flag.class), true));

        assertThat(plain).contains("said hi").doesNotContain("hi: hi");
    }

    @Test
    void extensionFieldsAppearInTheHover() {
        SpyglassApi api = mock(SpyglassApi.class);
        when(api.displayRenderer("say")).thenReturn(Optional.empty());
        ResultRenderer renderer = new ResultRenderer(api, configWithVerb("say", "said"));

        Instant now = Instant.now();
        ChatRecord chat = new ChatRecord(
                UUID.randomUUID(), "say", now, now.plusSeconds(60),
                Origin.plugin("WhisperNet"), Source.player(PLAYER_ID, "Alice"),
                new BlockLocation(WORLD_ID, "world", 10, 64, 20), "test",
                "#OOC", "hi", List.of(), java.util.Map.of("channel", "#OOC"));

        Component hover = extractHover(renderer.renderSingle(chat, EnumSet.noneOf(Flag.class), true));
        assertThat(hover).as("result line should carry a hover").isNotNull();
        String hoverPlain = PlainTextComponentSerializer.plainText().serialize(hover);
        // capitalizeKey turns the extension key into its label.
        assertThat(hoverPlain).contains("Channel").contains("#OOC");
    }

    private static Component extractHover(Component component) {
        HoverEvent<?> hover = component.hoverEvent();
        if (hover != null && hover.value() instanceof Component value) {
            return value;
        }
        for (Component child : component.children()) {
            Component found = extractHover(child);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    @Test
    void extendedFlagAppendsLocationLine() {
        SpyglassApi api = mock(SpyglassApi.class);
        when(api.displayRenderer("sculk")).thenReturn(Optional.empty());
        ResultRenderer renderer = new ResultRenderer(api, configWithVerb("sculk", "triggered"));

        Component rendered = renderer.renderSingle(useRecord(), EnumSet.of(Flag.EXTENDED), true);
        String plain = PlainTextComponentSerializer.plainText().serialize(rendered);

        assertThat(plain).contains("x: 10").contains("y: 64").contains("z: 20").contains("world: world");
    }

    @Test
    void singleRecordCarriesTeleportClickEvent() {
        SpyglassApi api = mock(SpyglassApi.class);
        when(api.displayRenderer("sculk")).thenReturn(Optional.empty());
        ResultRenderer renderer = new ResultRenderer(api, configWithVerb("sculk", "triggered"));

        Component rendered = renderer.renderSingle(useRecord(), EnumSet.noneOf(Flag.class), true);

        assertThat(findRunCommand(rendered))
                .as("click event on a single result should be /spyglass tele")
                .startsWith("/spyglass tele " + WORLD_ID);
    }

    @Test
    void groupedResultCarriesGlobalFlagIntoDrillDownWhenSearchWasGlobal() {
        SpyglassApi api = mock(SpyglassApi.class);
        when(api.displayRenderer("sculk")).thenReturn(Optional.empty());
        ResultRenderer renderer = new ResultRenderer(api, configWithVerb("sculk", "triggered"));

        Component rendered = renderer.renderAggregation(
                new net.medievalrp.spyglass.api.query.QueryResult.RecordAggregation(useRecord(), 4),
                EnumSet.of(Flag.GLOBAL), true);

        // Without -g the drill-down re-applies the default radius around the
        // operator's current position and finds nothing — the rows were
        // gathered globally. The expand click must carry -g forward.
        assertThat(findRunCommand(rendered))
                .as("global grouped drill-down must keep -g")
                .startsWith("/spyglass search ")
                .contains("a:sculk")
                .contains("p:Alice")
                .contains("-ng")
                .contains("-g");
    }

    @Test
    void groupedResultOmitsGlobalFlagWhenSearchWasNotGlobal() {
        SpyglassApi api = mock(SpyglassApi.class);
        when(api.displayRenderer("sculk")).thenReturn(Optional.empty());
        ResultRenderer renderer = new ResultRenderer(api, configWithVerb("sculk", "triggered"));

        Component rendered = renderer.renderAggregation(
                new net.medievalrp.spyglass.api.query.QueryResult.RecordAggregation(useRecord(), 4),
                EnumSet.noneOf(Flag.class), true);

        // A radius-scoped grouped search keeps its drill-down radius-scoped:
        // the trailing token is -ng, with no -g appended.
        String command = findRunCommand(rendered);
        assertThat(command).endsWith("-ng");
        assertThat(command).doesNotContain("-g ");
        assertThat(command.endsWith("-g")).isFalse();
    }

    private static net.medievalrp.spyglass.api.event.ContainerDepositRecord depositRecord(
            net.medievalrp.spyglass.api.event.StoredItem afterItem) {
        Instant now = Instant.now();
        return new net.medievalrp.spyglass.api.event.ContainerDepositRecord(
                UUID.randomUUID(), "deposit",
                now, now.plusSeconds(60),
                Origin.player(),
                Source.player(PLAYER_ID, "Alice"),
                new BlockLocation(WORLD_ID, "world", 10, 64, 20),
                "test",
                "IRON_HORSE_ARMOR", "CHEST", 0, 1,
                null, afterItem);
    }

    @Test
    void hoverShowsCustomItemNameLoreAndEnchants() {
        SpyglassApi api = mock(SpyglassApi.class);
        when(api.displayRenderer("deposit")).thenReturn(Optional.empty());
        ResultRenderer renderer = new ResultRenderer(api, configWithVerb("deposit", "deposited"));

        net.medievalrp.spyglass.api.event.StoredItem item =
                new net.medievalrp.spyglass.api.event.StoredItem(
                        0, "IRON_HORSE_ARMOR", null,
                        "Storm Caller",
                        List.of("Forged in the primordial deep", "+10 barding"),
                        List.of("protection=4", "unbreaking=3"));

        String hover = hoverPlain(renderer.renderSingle(
                depositRecord(item), EnumSet.noneOf(Flag.class), true));

        assertThat(hover).contains("Storm Caller");
        assertThat(hover).contains("primordial deep");
        assertThat(hover).contains("protection=4").contains("unbreaking=3");
    }

    @Test
    void hoverOmitsItemDetailForVanillaItem() {
        SpyglassApi api = mock(SpyglassApi.class);
        when(api.displayRenderer("deposit")).thenReturn(Optional.empty());
        ResultRenderer renderer = new ResultRenderer(api, configWithVerb("deposit", "deposited"));

        // No custom name, no lore, no enchants -> no item-detail lines.
        net.medievalrp.spyglass.api.event.StoredItem vanilla =
                new net.medievalrp.spyglass.api.event.StoredItem(0, "IRON_HORSE_ARMOR", null);

        String hover = hoverPlain(renderer.renderSingle(
                depositRecord(vanilla), EnumSet.noneOf(Flag.class), true));

        assertThat(hover)
                .doesNotContain("Item Name")
                .doesNotContain("Enchants")
                .doesNotContain("Lore");
    }

    @Test
    void hoverCapsLongLore() {
        SpyglassApi api = mock(SpyglassApi.class);
        when(api.displayRenderer("deposit")).thenReturn(Optional.empty());
        ResultRenderer renderer = new ResultRenderer(api, configWithVerb("deposit", "deposited"));

        List<String> lore = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            lore.add("lore line " + i);
        }
        net.medievalrp.spyglass.api.event.StoredItem item =
                new net.medievalrp.spyglass.api.event.StoredItem(
                        0, "IRON_HORSE_ARMOR", null, null, lore, List.of());

        String hover = hoverPlain(renderer.renderSingle(
                depositRecord(item), EnumSet.noneOf(Flag.class), true));

        assertThat(hover).contains("lore line 0").contains("lore line 11");
        // 12-line cap: the 13th line (index 12) is rolled into the "+N more".
        assertThat(hover).doesNotContain("lore line 12");
        assertThat(hover).contains("(+8 more)");
    }

    @Test
    void hoverShowsCustomDataTags() {
        SpyglassApi api = mock(SpyglassApi.class);
        when(api.displayRenderer("deposit")).thenReturn(Optional.empty());
        ResultRenderer renderer = new ResultRenderer(api, configWithVerb("deposit", "deposited"));

        // An item carrying ONLY custom_data (no name/lore/enchants) still
        // surfaces a Tags line (#140).
        net.medievalrp.spyglass.api.event.StoredItem item =
                new net.medievalrp.spyglass.api.event.StoredItem(
                        0, "PAPER", null, null, List.of(), List.of(),
                        "{quest:\"deliver_letter\"}");

        String hover = hoverPlain(renderer.renderSingle(
                depositRecord(item), EnumSet.noneOf(Flag.class), true));

        assertThat(hover).contains("Tags").contains("deliver_letter");
    }

    @Test
    void hoverCapsLongTags() {
        SpyglassApi api = mock(SpyglassApi.class);
        when(api.displayRenderer("deposit")).thenReturn(Optional.empty());
        ResultRenderer renderer = new ResultRenderer(api, configWithVerb("deposit", "deposited"));

        String big = "{data:\"" + "x".repeat(400) + "\"}";
        net.medievalrp.spyglass.api.event.StoredItem item =
                new net.medievalrp.spyglass.api.event.StoredItem(
                        0, "PAPER", null, null, List.of(), List.of(), big);

        String hover = hoverPlain(renderer.renderSingle(
                depositRecord(item), EnumSet.noneOf(Flag.class), true));

        // The hover previews custom_data with an ellipsis; the full blob is
        // only reachable via itags:, so it must not appear verbatim.
        assertThat(hover).contains("Tags").contains("…");
        assertThat(hover).doesNotContain(big);
    }

    @Test
    void rendersCustomEventWithVerbTargetMessageAndBagInHover() {
        SpyglassApi api = mock(SpyglassApi.class);
        when(api.displayRenderer("voice")).thenReturn(Optional.empty());
        ResultRenderer renderer = new ResultRenderer(api, configWithVerb("voice", "spoke"));

        Instant now = Instant.now();
        net.medievalrp.spyglass.api.event.CustomRecord record =
                new net.medievalrp.spyglass.api.event.CustomRecord(
                        UUID.randomUUID(), "voice", now, now.plusSeconds(60),
                        Origin.player(), Source.player(PLAYER_ID, "Alice"),
                        new BlockLocation(WORLD_ID, "world", 1, 64, 2), "srv",
                        "voice to 2 players", "hello there",
                        java.util.Map.of("voice_session_id", "42"));

        Component rendered = renderer.renderSingle(record, EnumSet.noneOf(Flag.class), true);
        String plain = PlainTextComponentSerializer.plainText().serialize(rendered);

        assertThat(plain).contains("Alice").contains("spoke")
                .contains("voice to 2 players").contains("hello there");
        // The bag rides into the hover as first-class key/value lines.
        String hover = hoverPlain(rendered);
        assertThat(hover).contains("session_id").contains("42");
    }

    @Test
    void deathRendersVictimDiedWithKillerOrCauseAsTarget() {
        SpyglassApi api = mock(SpyglassApi.class);
        when(api.displayRenderer("death")).thenReturn(Optional.empty());
        ResultRenderer renderer = new ResultRenderer(api, configWithVerb("death", "died"));

        Instant now = Instant.now();
        EntityDeathRecord death = new EntityDeathRecord(
                UUID.randomUUID(), "death", now, now.plusSeconds(60),
                Origin.environment("death:FALL"), Source.player(PLAYER_ID, "Alice"),
                new BlockLocation(WORLD_ID, "world", 1, 64, 2), "test",
                "FALL", "player", UUID.randomUUID(), "FALL", "FALL", null);

        String plain = PlainTextComponentSerializer.plainText().serialize(
                renderer.renderSingle(death, EnumSet.noneOf(Flag.class), true));

        assertThat(plain).contains("Alice").contains("died").contains("FALL");
    }

    @Test
    void killRendersKillerKilledVictim() {
        SpyglassApi api = mock(SpyglassApi.class);
        when(api.displayRenderer("kill")).thenReturn(Optional.empty());
        ResultRenderer renderer = new ResultRenderer(api, configWithVerb("kill", "killed"));

        Instant now = Instant.now();
        EntityHitRecord kill = new EntityHitRecord(
                UUID.randomUUID(), "kill", now, now.plusSeconds(60),
                Origin.player(), Source.player(PLAYER_ID, "Alice"),
                new BlockLocation(WORLD_ID, "world", 1, 64, 2), "test",
                "zombie", "zombie", UUID.randomUUID(), 6.0, false, null);

        String plain = PlainTextComponentSerializer.plainText().serialize(
                renderer.renderSingle(kill, EnumSet.noneOf(Flag.class), true));

        assertThat(plain).contains("Alice").contains("killed").contains("ZOMBIE");
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

    private static net.medievalrp.spyglass.api.event.JoinRecord joinRecord() {
        Instant now = Instant.now();
        return new net.medievalrp.spyglass.api.event.JoinRecord(
                UUID.randomUUID(), "join",
                now, now.plusSeconds(60),
                Origin.player(),
                Source.player(PLAYER_ID, "Alice"),
                new BlockLocation(WORLD_ID, "world", 10, 64, 20),
                "test",
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
        SpyglassApi api = mock(SpyglassApi.class);
        when(api.displayRenderer("join")).thenReturn(Optional.empty());
        ResultRenderer renderer = new ResultRenderer(api, configWithVerb("join", "joined"));

        Component rendered = renderer.renderSingle(joinRecord(), EnumSet.noneOf(Flag.class), true);

        assertThat(PlainTextComponentSerializer.plainText().serialize(rendered))
                .contains("203.0.113.7");
        assertThat(hoverPlain(rendered))
                .contains("IP: 203.0.113.7");
    }

    @Test
    void joinIpMasksInLineAndHoverWithoutIpPermission() {
        SpyglassApi api = mock(SpyglassApi.class);
        when(api.displayRenderer("join")).thenReturn(Optional.empty());
        ResultRenderer renderer = new ResultRenderer(api, configWithVerb("join", "joined"));

        Component rendered = renderer.renderSingle(joinRecord(), EnumSet.noneOf(Flag.class), false);

        String plain = PlainTextComponentSerializer.plainText().serialize(rendered);
        assertThat(plain)
                .contains(ResultRenderer.IP_HIDDEN)
                .doesNotContain("203.0.113.7");
        assertThat(hoverPlain(rendered))
                .contains("IP: " + ResultRenderer.IP_HIDDEN)
                .doesNotContain("203.0.113.7");
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

        Component rendered = renderer.renderSingle(useRecord(), EnumSet.noneOf(Flag.class), true);
        String plain = PlainTextComponentSerializer.plainText().serialize(rendered);

        // Falls back to the default SCULK_SENSOR target instead of throwing.
        assertThat(plain).contains("SCULK_SENSOR");
    }
}
