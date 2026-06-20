package net.medievalrp.spyglass.plugin.worldedit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FaweEditDedupeTest {

    @Test
    void repeatedCoordinateIsAcceptedOnce() {
        FaweEditDedupe dedupe = new FaweEditDedupe();

        assertThat(dedupe.mark(10, 64, -20)).isTrue();
        assertThat(dedupe.mark(10, 64, -20)).isFalse();
        assertThat(dedupe.size()).isEqualTo(1);
    }

    @Test
    void neighboringAndNegativeCoordinatesStayDistinct() {
        FaweEditDedupe dedupe = new FaweEditDedupe();

        assertThat(dedupe.mark(-30_000_000, -64, 30_000_000)).isTrue();
        assertThat(dedupe.mark(-30_000_000, -63, 30_000_000)).isTrue();
        assertThat(dedupe.mark(-29_999_999, -64, 30_000_000)).isTrue();
        assertThat(dedupe.mark(-30_000_000, -64, 29_999_999)).isTrue();
        assertThat(dedupe.mark(-30_000_000, -64, 30_000_000)).isFalse();
        assertThat(dedupe.size()).isEqualTo(4);
    }

    @Test
    void fullWorldBorderRangeDoesNotCollide() {
        FaweEditDedupe dedupe = new FaweEditDedupe();

        assertThat(dedupe.mark(-30_000_000, 64, 0)).isTrue();
        assertThat(dedupe.mark(-30_000_000 + (1 << 22), 64, 0)).isTrue();
        assertThat(dedupe.mark(0, 64, 30_000_000)).isTrue();
        assertThat(dedupe.mark(0, 64, 30_000_000 - (1 << 22))).isTrue();
        assertThat(dedupe.size()).isEqualTo(4);
    }

    @Test
    void growthKeepsExistingKeysDeduped() {
        FaweEditDedupe dedupe = new FaweEditDedupe();

        for (int i = 0; i < 20_000; i++) {
            assertThat(dedupe.mark(i - 10_000, i % 384 - 64, -i)).isTrue();
        }
        for (int i = 0; i < 20_000; i++) {
            assertThat(dedupe.mark(i - 10_000, i % 384 - 64, -i)).isFalse();
        }
        assertThat(dedupe.size()).isEqualTo(20_000);
    }
}
