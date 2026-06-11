package net.medievalrp.spyglass.api.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EventIdsTest {

    @Test
    void mintsVersion8VariantUuids() {
        UUID id = EventIds.newId();
        assertThat(id.version()).isEqualTo(8);
        assertThat(id.variant()).isEqualTo(2);
    }

    @Test
    void sequenceRoundTripsThroughTheUuid() {
        UUID id = EventIds.newId();
        long sequence = EventIds.sequenceOf(id);
        assertThat(EventIds.uuidOf(sequence)).isEqualTo(id);
    }

    @Test
    void sequencesAreStrictlyIncreasing() {
        long previous = EventIds.sequenceOf(EventIds.newId());
        for (int i = 0; i < 100_000; i++) {
            long next = EventIds.sequenceOf(EventIds.newId());
            assertThat(next)
                    .as("delta compression depends on a monotonic per-instance stream")
                    .isGreaterThan(previous);
            previous = next;
        }
    }

    @Test
    void burstOfIdsIsUnique() {
        // 200K in a tight loop exceeds the per-ms counter many times
        // over — the rollover spin must keep ids unique.
        Set<UUID> seen = new HashSet<>();
        for (int i = 0; i < 200_000; i++) {
            assertThat(seen.add(EventIds.newId())).isTrue();
        }
    }

    @Test
    void foreignUuidsFoldDeterministically() {
        UUID foreign = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        long first = EventIds.sequenceOf(foreign);
        long second = EventIds.sequenceOf(foreign);
        assertThat(first).isEqualTo(second);
        // Folded sequences stay inside the 62-bit storage domain.
        assertThat(first).isBetween(0L, 0x3FFFFFFFFFFFFFFFL);
    }

    @Test
    void instanceBitsSeparateStreams() {
        EventIds.bindInstance(3);
        long a = EventIds.sequenceOf(EventIds.newId());
        EventIds.bindInstance(7);
        long b = EventIds.sequenceOf(EventIds.newId());
        assertThat((a >>> 14) & 0xFF).isEqualTo(3);
        assertThat((b >>> 14) & 0xFF).isEqualTo(7);
    }
}
