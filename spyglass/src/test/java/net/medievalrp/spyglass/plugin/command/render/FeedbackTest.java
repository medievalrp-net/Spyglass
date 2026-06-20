package net.medievalrp.spyglass.plugin.command.render;

import static org.assertj.core.api.Assertions.assertThat;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

/**
 * Guards the command feedback components. The static {@link Feedback#PREFIX}
 * initializer (and the per-message builders) build Adventure components via
 * {@code Component.text()...asComponent()} - {@code asComponent()} rather than
 * {@code build()} because Paper 26.2+ ships an Adventure whose
 * {@code TextComponent.Builder.build()} descriptor changed, which threw
 * {@code NoSuchMethodError} from every command. {@code ComponentLike.asComponent()}
 * is stable across Adventure versions. (The cross-version runtime guard is the
 * RCON WorldEdit-rollback test under scripts/.)
 */
class FeedbackTest {

    private static String plain(Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    @Test
    void prefixInitializesToHouseStyle() {
        assertThat(plain(Feedback.PREFIX)).isEqualTo("«Spyglass»");
    }

    @Test
    void errorPrefixesAndLabelsTheMessage() {
        assertThat(plain(Feedback.error("boom"))).isEqualTo("«Spyglass» (Error) boom");
    }

    @Test
    void infoWarnSuccessCarryPrefixAndMessage() {
        assertThat(plain(Feedback.info("hi"))).contains("«Spyglass»").contains("hi");
        assertThat(plain(Feedback.warn("careful"))).contains("«Spyglass»").contains("careful");
        assertThat(plain(Feedback.success("done"))).contains("«Spyglass»").contains("done");
    }

    @Test
    void bonusIsPlainWithoutPrefix() {
        assertThat(plain(Feedback.bonus("skip reason"))).isEqualTo("skip reason");
    }
}
