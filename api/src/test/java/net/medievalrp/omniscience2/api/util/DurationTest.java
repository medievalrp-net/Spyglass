package net.medievalrp.omniscience2.api.util;

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
    }

    @Test
    void parsesCompositeDurations() {
        assertThat(Duration.parse("4w3d").seconds()).isEqualTo((4L * 7L + 3L) * 86_400L);
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
