package net.medievalrp.spyglass.plugin.listener.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import net.medievalrp.spyglass.plugin.config.SpyglassConfig;
import org.junit.jupiter.api.Test;

class CommandRedactionTest {

    private final CommandRedaction defaults =
            new CommandRedaction(SpyglassConfig.DEFAULT_COMMAND_REDACT);

    @Test
    void masksArgsOfListedCommand() {
        assertThat(defaults.apply("login", "/login hunter2"))
                .isEqualTo("/login ***");
    }

    @Test
    void matchIsCaseInsensitiveAndPreservesOriginalSpelling() {
        assertThat(defaults.apply("LOGIN", "/LOGIN hunter2"))
                .isEqualTo("/LOGIN ***");
    }

    @Test
    void slashlessConsoleFormMasks() {
        assertThat(defaults.apply("login", "login hunter2"))
                .isEqualTo("login ***");
    }

    @Test
    void namespacedHeadMatchesSimpleName() {
        assertThat(defaults.apply("authme:login", "/authme:login hunter2"))
                .isEqualTo("/authme:login ***");
    }

    @Test
    void multipleArgsCollapseToOneMask() {
        assertThat(defaults.apply("register", "/register pw pw"))
                .isEqualTo("/register ***");
    }

    @Test
    void differentHeadSharingPrefixIsNotMasked() {
        assertThat(defaults.apply("loginfo", "/loginfo verbose"))
                .isEqualTo("/loginfo verbose");
    }

    @Test
    void unlistedCommandRecordsVerbatim() {
        assertThat(defaults.apply("give", "/give Alice diamond 64"))
                .isEqualTo("/give Alice diamond 64");
    }

    @Test
    void argLessListedCommandRecordsVerbatim() {
        assertThat(defaults.apply("login", "/login")).isEqualTo("/login");
        assertThat(defaults.apply("login", "/login ")).isEqualTo("/login ");
    }

    @Test
    void emptyListIsTheOptOut() {
        CommandRedaction off = new CommandRedaction(List.of());
        assertThat(off.apply("login", "/login hunter2"))
                .isEqualTo("/login hunter2");
    }

    @Test
    void configEntriesNormalizeSlashCaseAndWhitespace() {
        CommandRedaction redaction = new CommandRedaction(List.of(" /Login "));
        assertThat(redaction.apply("login", "/login hunter2"))
                .isEqualTo("/login ***");
    }
}
