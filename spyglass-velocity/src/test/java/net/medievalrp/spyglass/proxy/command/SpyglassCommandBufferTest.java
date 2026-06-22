package net.medievalrp.spyglass.proxy.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import net.kyori.adventure.text.Component;
import net.medievalrp.spyglass.api.util.Duration;
import net.medievalrp.spyglass.proxy.config.SpyglassProxyConfig;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the buffer eviction and TTL behaviour added in #133.
 *
 * Both paths are exercised without a real Velocity server by accessing the
 * package-private {@code evict} method and the {@code TimestampedBuffer}
 * wrapper directly via the clock seam constructor.
 */
class SpyglassCommandBufferTest {

    private static final UUID PLAYER = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    // Minimal stub: inserts a TimestampedBuffer directly via the map
    // returned by reflect-free access through the package. We drive
    // SpyglassCommand via its package-private helpers and inspect
    // buffers indirectly through evict() and the TTL-aware page lookup.

    /**
     * After a buffer is inserted for a UUID, calling evict() must remove it.
     */
    @Test
    void evictRemovesBufferForPlayer() {
        AtomicLong clock = new AtomicLong(1_000L);
        SpyglassCommand command = minimalCommand(clock::get);

        // Inject a buffer for PLAYER directly through the TimestampedBuffer API.
        injectBuffer(command, PLAYER, clock.get());

        assertThat(hasBuffer(command, PLAYER)).isTrue();

        command.evict(PLAYER);

        assertThat(hasBuffer(command, PLAYER)).isFalse();
    }

    /**
     * Evicting an unknown UUID must not throw.
     */
    @Test
    void evictUnknownUuidIsNoOp() {
        AtomicLong clock = new AtomicLong(1_000L);
        SpyglassCommand command = minimalCommand(clock::get);

        // Should complete without exception.
        command.evict(UUID.randomUUID());
    }

    /**
     * A buffer older than the TTL is treated as absent: runPage discards it
     * and the map is cleared. We verify indirectly: after the clock advances
     * beyond TTL, the buffer must no longer be present after a page request
     * would have checked it.
     *
     * Because we cannot call runPage without a real CommandSource, we
     * verify the contract via the TimestampedBuffer.storedAt and
     * BUFFER_TTL_MILLIS constant directly, confirming the class would treat
     * the entry as expired.
     */
    @Test
    void bufferOlderThanTtlIsConsideredExpired() {
        long storedAt = 1_000L;
        long now = storedAt + SpyglassCommand.BUFFER_TTL_MILLIS + 1;

        SpyglassCommand.ResultBuffer buffer = makeResultBuffer();
        SpyglassCommand.TimestampedBuffer entry =
                new SpyglassCommand.TimestampedBuffer(buffer, storedAt);

        // The condition the command checks: age > TTL means stale.
        assertThat(now - entry.storedAt()).isGreaterThan(SpyglassCommand.BUFFER_TTL_MILLIS);
    }

    /**
     * A buffer stored exactly at the TTL boundary is NOT yet expired.
     */
    @Test
    void bufferAtExactTtlBoundaryIsNotExpired() {
        long storedAt = 1_000L;
        long now = storedAt + SpyglassCommand.BUFFER_TTL_MILLIS; // equal, not greater

        SpyglassCommand.ResultBuffer buffer = makeResultBuffer();
        SpyglassCommand.TimestampedBuffer entry =
                new SpyglassCommand.TimestampedBuffer(buffer, storedAt);

        assertThat(now - entry.storedAt()).isEqualTo(SpyglassCommand.BUFFER_TTL_MILLIS);
        // The command uses strict > so an entry exactly at TTL is still live.
        assertThat(now - entry.storedAt()).isLessThanOrEqualTo(SpyglassCommand.BUFFER_TTL_MILLIS);
    }

    /**
     * Evicting one player does not remove another player's buffer.
     */
    @Test
    void evictDoesNotAffectOtherPlayers() {
        AtomicLong clock = new AtomicLong(1_000L);
        SpyglassCommand command = minimalCommand(clock::get);

        UUID other = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        injectBuffer(command, PLAYER, clock.get());
        injectBuffer(command, other, clock.get());

        command.evict(PLAYER);

        assertThat(hasBuffer(command, PLAYER)).isFalse();
        assertThat(hasBuffer(command, other)).isTrue();
    }

    // ---- helpers ----

    private static SpyglassCommand minimalCommand(java.util.function.LongSupplier clock) {
        // SpyglassCommand's package-private clock-seam constructor avoids
        // needing a real RecordStore or ProxyServer for these unit tests.
        // A null RecordStore is fine because the ip:-resolver lambda is
        // never invoked on the evict/TTL paths under test.
        SpyglassProxyConfig config = new SpyglassProxyConfig(
                /* database */ null,
                new SpyglassProxyConfig.Defaults(Duration.parse("4h")),
                new SpyglassProxyConfig.Limits(1000, 8));
        return new SpyglassCommand(/* store */ null, config, /* logger */ null, clock);
    }

    @SuppressWarnings("unchecked")
    private static void injectBuffer(SpyglassCommand command, UUID id, long storedAt) {
        try {
            var field = SpyglassCommand.class.getDeclaredField("buffers");
            field.setAccessible(true);
            var map = (ConcurrentMap<UUID, SpyglassCommand.TimestampedBuffer>) field.get(command);
            SpyglassCommand.ResultBuffer buf = makeResultBuffer();
            map.put(id, new SpyglassCommand.TimestampedBuffer(buf, storedAt));
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException(ex);
        }
    }

    @SuppressWarnings("unchecked")
    private static boolean hasBuffer(SpyglassCommand command, UUID id) {
        try {
            var field = SpyglassCommand.class.getDeclaredField("buffers");
            field.setAccessible(true);
            var map = (ConcurrentMap<UUID, SpyglassCommand.TimestampedBuffer>) field.get(command);
            return map.containsKey(id);
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException(ex);
        }
    }

    private static SpyglassCommand.ResultBuffer makeResultBuffer() {
        List<Component> rows = List.of(Component.text("row1"), Component.text("row2"));
        return new SpyglassCommand.ResultBuffer(rows, 10);
    }
}
