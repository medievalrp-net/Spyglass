package net.medievalrp.spyglass.api.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DurationTest {

    @Test
    void parsesEverySupportedUnit() {
        assertThat(Duration.parse("1s").seconds()).isEqualTo(1L);
        assertThat(Duration.parse("1m").seconds()).isEqualTo(60L);
        assertThat(Duration.parse("1h").seconds()).isEqualTo(3_600L);
        assertThat(Duration.parse("1d").seconds()).isEqualTo(86_400L);
        assertThat(Duration.parse("1w").seconds()).isEqualTo(604_800L);
        assertThat(Duration.parse("1mo").seconds()).isEqualTo(30L * 86_400L);
    }

    @Test
    void parsesCompositeDurations() {
        assertThat(Duration.parse("4w3d").seconds()).isEqualTo((4L * 7L + 3L) * 86_400L);
    }

    @Test
    void monthDoesNotShadowMinute() {
        // "mo" is ordered before the single-char class (#271): 1mo is a
        // month, 1m stays a minute, and a bare trailing "o" still fails.
        assertThat(Duration.parse("2mo").seconds()).isEqualTo(60L * 86_400L);
        assertThat(Duration.parse("1mo30m").seconds())
                .isEqualTo(30L * 86_400L + 30L * 60L);
        assertThatThrownBy(() -> Duration.parse("1month"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void compactRendersShortestForm() {
        assertThat(Duration.parse("26w").compact()).isEqualTo("26w");
        assertThat(Duration.parse("7d").compact()).isEqualTo("1w");
        assertThat(Duration.parse("4h30m").compact()).isEqualTo("4h30m");
        assertThat(Duration.parse("1mo").compact()).isEqualTo("4w2d");
        assertThat(new Duration(0L).compact()).isEqualTo("0s");
    }

    @Test
    void rejectsInvalidInput() {
        assertThatThrownBy(() -> Duration.parse(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Duration.parse("4x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Duration.parse("w4"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsOverflow() {
        assertThatThrownBy(() -> Duration.parse(Long.MAX_VALUE + "w"))
                .isInstanceOf(ArithmeticException.class);
    }
}
