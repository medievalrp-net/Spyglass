package net.medievalrp.spyglass.plugin.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link FallingBlockTracker#purgeExpired()} -- #128.
 *
 * <p>CELLS is static, so each test clears it in {@link #reset()} to prevent
 * bleed between test methods. Expired entries are injected via the
 * package-private {@code track(..., expiresAt)} overload so tests never have
 * to sleep or manipulate wall-clock time.
 */
class FallingBlockTrackerTest {

    private static final UUID WORLD = UUID.randomUUID();
    private static final UUID PLAYER = UUID.randomUUID();

    @BeforeEach
    void reset() {
        FallingBlockTracker.clear();
    }

    // -----------------------------------------------------------------------
    // purgeExpired() -- core contract
    // -----------------------------------------------------------------------

    @Test
    void purgeExpired_removesOnlyExpiredEntries() {
        long now = System.currentTimeMillis();
        long alreadyExpired = now - 1L;   // expired 1 ms ago
        long stillFresh = now + 30_000L;  // expires in 30 s

        // Insert two expired cells and two fresh cells.
        FallingBlockTracker.track(WORLD, 0, 64, 0, PLAYER, "Alice", alreadyExpired);
        FallingBlockTracker.track(WORLD, 1, 64, 0, PLAYER, "Alice", alreadyExpired);
        FallingBlockTracker.track(WORLD, 2, 64, 0, PLAYER, "Bob", stillFresh);
        FallingBlockTracker.track(WORLD, 3, 64, 0, PLAYER, "Bob", stillFresh);

        assertThat(FallingBlockTracker.size()).isEqualTo(4);

        FallingBlockTracker.purgeExpired();

        assertThat(FallingBlockTracker.size())
                .as("only the two expired entries should be removed")
                .isEqualTo(2);

        // Fresh entries must still be consumable.
        assertThat(FallingBlockTracker.consume(WORLD, 2, 64, 0)).isPresent();
        assertThat(FallingBlockTracker.consume(WORLD, 3, 64, 0)).isPresent();
    }

    @Test
    void purgeExpired_removesAllEntriesWhenAllExpired() {
        long expired = System.currentTimeMillis() - 1L;

        FallingBlockTracker.track(WORLD, 10, 10, 10, PLAYER, "Alice", expired);
        FallingBlockTracker.track(WORLD, 20, 20, 20, PLAYER, "Bob", expired);

        FallingBlockTracker.purgeExpired();

        assertThat(FallingBlockTracker.size()).isZero();
    }

    @Test
    void purgeExpired_doesNotRemoveFreshEntries() {
        long fresh = System.currentTimeMillis() + 30_000L;

        FallingBlockTracker.track(WORLD, 5, 5, 5, PLAYER, "Charlie", fresh);

        FallingBlockTracker.purgeExpired();

        assertThat(FallingBlockTracker.size()).isEqualTo(1);
        assertThat(FallingBlockTracker.consume(WORLD, 5, 5, 5)).isPresent();
    }

    @Test
    void purgeExpired_isIdempotentOnEmptyMap() {
        // Must not throw when there is nothing to purge.
        FallingBlockTracker.purgeExpired();
        FallingBlockTracker.purgeExpired();

        assertThat(FallingBlockTracker.size()).isZero();
    }

    // -----------------------------------------------------------------------
    // consume() -- TTL check on read (existing contract, regression guard)
    // -----------------------------------------------------------------------

    @Test
    void consume_returnsEmptyForExpiredEntry() {
        long expired = System.currentTimeMillis() - 1L;
        FallingBlockTracker.track(WORLD, 0, 0, 0, PLAYER, "Alice", expired);

        assertThat(FallingBlockTracker.consume(WORLD, 0, 0, 0)).isEmpty();
        // The expired entry must have been removed by consume().
        assertThat(FallingBlockTracker.size()).isZero();
    }

    @Test
    void consume_returnsTrackedForFreshEntry() {
        // Use the normal (wall-clock) track so this exercises the real path.
        FallingBlockTracker.track(WORLD, 7, 64, 7, PLAYER, "Dave");

        assertThat(FallingBlockTracker.consume(WORLD, 7, 64, 7))
                .isPresent()
                .hasValueSatisfying(t -> {
                    assertThat(t.playerId()).isEqualTo(PLAYER);
                    assertThat(t.playerName()).isEqualTo("Dave");
                });
    }

    @Test
    void consume_returnsEmptyForUnknownKey() {
        assertThat(FallingBlockTracker.consume(WORLD, 99, 0, 99)).isEmpty();
    }

    @Test
    void consume_isOneShot() {
        FallingBlockTracker.track(WORLD, 1, 1, 1, PLAYER, "Eve");

        assertThat(FallingBlockTracker.consume(WORLD, 1, 1, 1)).isPresent();
        // Second consume of the same key must return empty.
        assertThat(FallingBlockTracker.consume(WORLD, 1, 1, 1)).isEmpty();
    }

    // -----------------------------------------------------------------------
    // Interaction: purge before consume
    // -----------------------------------------------------------------------

    @Test
    void purgeExpired_thenConsumeReturnsEmpty() {
        long expired = System.currentTimeMillis() - 1L;
        FallingBlockTracker.track(WORLD, 0, 64, 0, PLAYER, "Fiona", expired);

        FallingBlockTracker.purgeExpired();

        // Entry was purged; consume must not find it.
        assertThat(FallingBlockTracker.consume(WORLD, 0, 64, 0)).isEmpty();
    }
}
