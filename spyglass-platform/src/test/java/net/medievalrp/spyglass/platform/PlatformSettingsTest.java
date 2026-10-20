package net.medievalrp.spyglass.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PlatformSettingsTest {

    @Test
    void theDefaultIsOff() {
        assertThat(PlatformSettings.defaults().enabled()).isFalse();
        assertThat(PlatformSettings.from(Map.of()).enabled()).isFalse();
        assertThat(PlatformSettings.from(null).enabled()).isFalse();
    }

    @Test
    void readsTheConfigMap() {
        PlatformSettings settings = PlatformSettings.from(Map.of(
                "enabled", true,
                "include-actions", List.of("BREAK", "place"),
                "exclude-actions", List.of("Say"),
                "redact-commands", List.of("Login"),
                "private-commands", List.of("MSG"),
                "publish-rollbacks", false));

        assertThat(settings.enabled()).isTrue();
        assertThat(settings.publishRollbacks()).isFalse();
        // Lower cased on the way in, so a config written by hand matches the catalog's
        // mixed conventions.
        assertThat(settings.include()).containsExactlyInAnyOrder("break", "place");
        assertThat(settings.publishes("Break")).isTrue();
        assertThat(settings.publishes("say")).isFalse();
        assertThat(settings.redactCommands()).containsExactly("login");
    }

    @Test
    void anUnknownKeyIsIgnoredRatherThanRejected() {
        // This module ships inside a jar that may be older or newer than the config
        // beside it; refusing to start over a key from the other version would turn a
        // config edit into an outage of the whole integration.
        PlatformSettings settings = PlatformSettings.from(Map.of(
                "enabled", true, "something-from-a-later-version", "yes"));

        assertThat(settings.enabled()).isTrue();
    }

    @Test
    void aWrongTypedValueFallsBackToTheDefault() {
        PlatformSettings settings = PlatformSettings.from(Map.of(
                "enabled", "true", "redact-commands", "login"));

        assertThat(settings.enabled()).isTrue();
        // Not a list: the protective default stands rather than an empty set, because an
        // empty redaction list publishes passwords.
        assertThat(settings.redactCommands()).contains("login", "register", "2fa");
    }

    @Test
    void readsBothTheHoconSafeKeysAndTheSpecsReservedOnes() {
        // `include` is a reserved word in HOCON, so the config file spells it
        // include-actions. A map built straight from the Crusalis spec spells it the
        // other way, and both have to work — the bug that taught us this stopped the
        // whole plugin from booting.
        PlatformSettings renamed = PlatformSettings.from(Map.of(
                "enabled", true, "include-actions", List.of("break"),
                "exclude-actions", List.of("say")));
        assertThat(renamed.publishes("break")).isTrue();
        assertThat(renamed.publishes("say")).isFalse();

        PlatformSettings reserved = PlatformSettings.from(Map.of(
                "enabled", true, "include", List.of("break"), "exclude", List.of("say")));
        assertThat(reserved.publishes("break")).isTrue();
        assertThat(reserved.publishes("say")).isFalse();
    }

    @Test
    void starIncludesEverythingAndExcludeStillWins() {
        PlatformSettings settings = PlatformSettings.from(Map.of(
                "enabled", true, "include-actions", List.of("*"),
                "exclude-actions", List.of("move")));

        assertThat(settings.publishes("break")).isTrue();
        assertThat(settings.publishes("move")).isFalse();
        assertThat(settings.publishes(null)).isFalse();
    }
}
