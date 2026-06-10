package net.medievalrp.spyglass.api.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EventIdsTest {

    @Test
    void mintsValidVersion7Uuids() {
        UUID id = EventIds.newId();
        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
    }

    @Test
    void timestampPrefixIsCurrentAndOrdered() throws Exception {
        long before = System.currentTimeMillis();
        UUID first = EventIds.newId();
        Thread.sleep(5);
        UUID second = EventIds.newId();
        long after = System.currentTimeMillis();

        long firstMs = first.getMostSignificantBits() >>> 16;
        long secondMs = second.getMostSignificantBits() >>> 16;
        assertThat(firstMs).isBetween(before, after);
        assertThat(secondMs).isGreaterThan(firstMs);
    }

    @Test
    void burstOfIdsIsUnique() {
        Set<UUID> seen = new HashSet<>();
        for (int i = 0; i < 200_000; i++) {
            assertThat(seen.add(EventIds.newId())).isTrue();
        }
    }
}
