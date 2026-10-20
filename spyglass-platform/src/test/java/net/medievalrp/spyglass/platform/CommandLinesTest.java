package net.medievalrp.spyglass.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

class CommandLinesTest {

    private static final Set<String> REDACT = Set.of("login", "register", "2fa");
    private static final Set<String> PRIVATE = Set.of("msg", "tell", "w");

    @Test
    void redactsTheArgumentsAndKeepsTheCommand() {
        assertThat(CommandLines.redact("/login hunter2", REDACT))
                .isEqualTo("/login <redacted>");
        // Several arguments, one replacement: a password with a space in it must not
        // leave its tail behind.
        assertThat(CommandLines.redact("/register hunter2 hunter2", REDACT))
                .isEqualTo("/register <redacted>");
    }

    @Test
    void leavesOtherCommandsAlone() {
        assertThat(CommandLines.redact("/home town", REDACT)).isEqualTo("/home town");
    }

    @Test
    void redactsAPluginQualifiedCommandToo() {
        // /authme:login is the same command, and a redaction list that misses the
        // qualified spelling is a redaction list that does not work.
        assertThat(CommandLines.redact("/authme:login hunter2", REDACT))
                .isEqualTo("/authme:login <redacted>");
    }

    @Test
    void handlesACommandWithNoArguments() {
        assertThat(CommandLines.redact("/login", REDACT)).isEqualTo("/login");
    }

    @Test
    void namesACommand() {
        assertThat(CommandLines.name("/MSG Someone hello")).isEqualTo("msg");
        assertThat(CommandLines.name("msg Someone hello")).isEqualTo("msg");
        assertThat(CommandLines.name("/essentials:msg x")).isEqualTo("msg");
        assertThat(CommandLines.name("")).isEmpty();
        assertThat(CommandLines.name(null)).isEmpty();
    }

    @Test
    void marksAPrivateCommand() {
        assertThat(CommandLines.isPrivate("/msg Someone hello", PRIVATE)).isTrue();
        assertThat(CommandLines.isPrivate("/w Someone hello", PRIVATE)).isTrue();
        assertThat(CommandLines.isPrivate("/say hello", PRIVATE)).isFalse();
    }

    @Test
    void anEmptyListMeansNoRedactionRatherThanEverything() {
        assertThat(CommandLines.redact("/login hunter2", Set.of()))
                .isEqualTo("/login hunter2");
        assertThat(CommandLines.isPrivate("/msg x y", Set.of())).isFalse();
    }
}
