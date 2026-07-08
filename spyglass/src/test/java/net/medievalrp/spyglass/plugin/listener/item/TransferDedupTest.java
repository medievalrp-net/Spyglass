package net.medievalrp.spyglass.plugin.listener.item;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The automated-transfer throttle (#226): a repeating (source, dest, material)
 * hopper flow must collapse to at most one record per window, or a farm line
 * floods the store. Time is injected so the window behaviour is deterministic.
 */
class TransferDedupTest {

    private static TransferDedup.Key key(String material) {
        return new TransferDedup.Key("world", 0, 64, 0, 0, 63, 0, material);
    }

    @Test
    void logsFirstThenSuppressesWithinWindow() {
        TransferDedup dedup = new TransferDedup(1000L, 1000);
        TransferDedup.Key k = key("COBBLESTONE");
        assertThat(dedup.shouldLog(k, 0L)).isTrue();
        assertThat(dedup.shouldLog(k, 200L)).isFalse();
        assertThat(dedup.shouldLog(k, 999L)).isFalse();
    }

    @Test
    void reArmsOnceTheWindowElapses() {
        TransferDedup dedup = new TransferDedup(1000L, 1000);
        TransferDedup.Key k = key("COBBLESTONE");
        assertThat(dedup.shouldLog(k, 0L)).isTrue();
        assertThat(dedup.shouldLog(k, 1000L)).isTrue();   // window elapsed, re-arm
        assertThat(dedup.shouldLog(k, 1500L)).isFalse();  // suppressed again
    }

    @Test
    void distinctKeysAreIndependent() {
        TransferDedup dedup = new TransferDedup(1000L, 1000);
        assertThat(dedup.shouldLog(key("COBBLESTONE"), 0L)).isTrue();
        assertThat(dedup.shouldLog(key("DIRT"), 0L)).isTrue();
        // A different destination coordinate is a different transfer.
        TransferDedup.Key otherDest = new TransferDedup.Key("world", 0, 64, 0, 5, 63, 0, "COBBLESTONE");
        assertThat(dedup.shouldLog(otherDest, 0L)).isTrue();
    }

    @Test
    void collapsesASustainedStreamToOnePerWindow() {
        TransferDedup dedup = new TransferDedup(1000L, 1000);
        TransferDedup.Key k = key("COBBLESTONE");
        int logged = 0;
        // A hopper "moving" every 25 ms for 2.5 s: 100 ticks.
        for (int t = 0; t < 2500; t += 25) {
            if (dedup.shouldLog(k, t)) {
                logged++;
            }
        }
        // Windows anchored at 0, 1000, 2000 -> three logged, not one hundred.
        assertThat(logged).isEqualTo(3);
    }

    @Test
    void purgeExpiredDropsStaleKeys() {
        TransferDedup dedup = new TransferDedup(1000L, 1000);
        dedup.shouldLog(key("COBBLESTONE"), 0L);
        dedup.shouldLog(key("DIRT"), 0L);
        assertThat(dedup.size()).isEqualTo(2);

        dedup.purgeExpired(500L);   // still inside the window: nothing dropped
        assertThat(dedup.size()).isEqualTo(2);

        dedup.purgeExpired(1000L);  // window elapsed for both: dropped
        assertThat(dedup.size()).isZero();
    }

    @Test
    void purgeCannotResurrectSuppression() {
        // Purging a key that has expired is a no-op for correctness: the next
        // transfer logs either way. Guard against a regression that would purge
        // a still-live anchor and let a farm burst through.
        TransferDedup dedup = new TransferDedup(1000L, 1000);
        TransferDedup.Key k = key("COBBLESTONE");
        assertThat(dedup.shouldLog(k, 0L)).isTrue();
        dedup.purgeExpired(500L);                     // live anchor kept
        assertThat(dedup.shouldLog(k, 600L)).isFalse();
    }
}
