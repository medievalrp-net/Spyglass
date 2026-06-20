package net.medievalrp.spyglass.plugin.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Guards the memory bound of {@link FallingBlockTracker} (#128). Cells whose
 * falling block never lands at its origin (shatter over water/lava, fall into
 * the void, removed by /kill) are never {@code consume}d, so eviction is the
 * only thing keeping the map from growing without limit.
 */
class FallingBlockTrackerTest {

    private final AtomicLong now = new AtomicLong(1_000L);

    @BeforeEach
    void useFakeClock() {
        FallingBlockTracker.clear();
        FallingBlockTracker.setClockForTest(now::get);
    }

    @AfterEach
    void restoreClock() {
        FallingBlockTracker.clear();
        FallingBlockTracker.setClockForTest(null);
    }

    @Test
    void purgeExpiredDropsCellsPastTtl() {
        UUID world = UUID.randomUUID();
        for (int i = 0; i < 50; i++) {
            FallingBlockTracker.track(world, i, 64, 0, UUID.randomUUID(), "p" + i);
        }
        assertThat(FallingBlockTracker.size()).isEqualTo(50);

        // Advance past the 30 s TTL: none of these landed (no consume call).
        now.addAndGet(31_000L);
        FallingBlockTracker.purgeExpired();

        assertThat(FallingBlockTracker.size()).isZero();
    }

    @Test
    void trackSelfPurgesSoUnconsumedCellsCannotGrowWithoutBound() {
        UUID world = UUID.randomUUID();

        // A burst of cascade cells that all shatter and never land.
        for (int i = 0; i < 2_000; i++) {
            FallingBlockTracker.track(world, i, 64, 0, UUID.randomUUID(), "p" + i);
        }
        int afterBurst = FallingBlockTracker.size();

        // Time moves past their TTL, then a steady trickle of fresh cells keeps
        // coming (more terrain edits). Without the amortized purge in track(),
        // the 2 000 dead cells would sit forever and the map would only grow.
        now.addAndGet(31_000L);
        for (int i = 0; i < 2_000; i++) {
            FallingBlockTracker.track(world, 100_000 + i, 64, 0, UUID.randomUUID(), "q" + i);
        }

        // The dead burst has been reclaimed: the live set reflects only the
        // recent fresh cells, not burst + trickle accumulated.
        assertThat(FallingBlockTracker.size())
                .as("expired cells must not accumulate across track() calls")
                .isLessThan(afterBurst + 1_024);
    }

    @Test
    void freshCellStillConsumableAfterSelfPurge() {
        UUID world = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        FallingBlockTracker.track(world, 7, 70, 7, player, "alex");

        // Drive enough tracks to trigger the amortized purge; the fresh cell
        // is within TTL so it must survive and stay attributable.
        for (int i = 0; i < 1_100; i++) {
            FallingBlockTracker.track(world, 200_000 + i, 64, 0, UUID.randomUUID(), "x");
        }

        assertThat(FallingBlockTracker.consume(world, 7, 70, 7))
                .get()
                .extracting(FallingBlockTracker.Tracked::playerId)
                .isEqualTo(player);
    }
}
