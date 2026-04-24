package net.medievalrp.spyglass.plugin.listener;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import net.medievalrp.spyglass.api.event.Origin;
import net.medievalrp.spyglass.api.event.RecordContext;
import net.medievalrp.spyglass.api.event.Source;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.api.util.Duration;
import org.junit.jupiter.api.Test;

/**
 * TTL contract unit tests for {@link RecordingSupport}.
 *
 * <p>Every record that lands in Mongo carries an {@code expiresAt} field
 * that the cluster-side TTL monitor reads to evict aged-out rows. If
 * {@link RecordingSupport#expiresAt} (and the {@link #context} /
 * {@code playerContext} factories that share the same computation) ever
 * drift from {@code occurred + retention}, data will either linger past
 * the configured retention or get evicted early — both silent failures.
 *
 * <p>Retention changes take effect per-record at write time, so these
 * tests also cover rotating the configured retention without
 * re-encoding old records.
 */
class RecordingSupportTest {

    private static final java.util.UUID WORLD = java.util.UUID.fromString(
            "77777777-7777-7777-7777-777777777777");
    private static final BlockLocation LOC = new BlockLocation(WORLD, "world", 0, 64, 0);

    @Test
    void expiresAtAddsConfiguredRetentionToOccurredInstant() {
        RecordingSupport support = new RecordingSupport(Duration.parse("30d"));
        Instant occurred = Instant.parse("2026-04-23T12:00:00Z");

        Instant expires = support.expiresAt(occurred);

        Instant expected = occurred.plus(30, ChronoUnit.DAYS);
        assertThat(expires).isEqualTo(expected);
    }

    @Test
    void expiresAtHonoursSecondPrecisionForSubDayRetentions() {
        // 1-hour retention is below the Duration parser's day boundary;
        // prove the plus/minus path stays precise at the second level so
        // retention = 1h doesn't get rounded to 0 days.
        RecordingSupport support = new RecordingSupport(Duration.parse("1h"));
        Instant occurred = Instant.parse("2026-04-23T12:00:00Z");

        Instant expires = support.expiresAt(occurred);

        assertThat(expires).isEqualTo(occurred.plusSeconds(3600));
    }

    @Test
    void contextFactoryStampsExpiresAtFromNow() {
        // context() reads Instant.now() internally. We can't fake that
        // here without adding a clock seam (yet — see note below), but
        // we can bracket the real call and assert expiresAt lands
        // within the bracketed window + retention. Tolerates up to 2s
        // of wall-clock skew on the machine running the test.
        Duration retention = Duration.parse("7d");
        RecordingSupport support = new RecordingSupport(retention);
        Origin origin = Origin.player();
        Source source = Source.player(java.util.UUID.randomUUID(), "Alice");

        Instant before = Instant.now();
        RecordContext ctx = support.context(origin, source, LOC);
        Instant after = Instant.now();

        long retSec = retention.seconds();
        assertThat(ctx.expiresAt()).isBetween(
                before.plusSeconds(retSec).minusSeconds(2),
                after.plusSeconds(retSec).plusSeconds(2));
        // occurred must be inside the same bracket — if it isn't, the
        // retention drift test below would be meaningless.
        assertThat(ctx.occurred()).isBetween(before.minusSeconds(2), after.plusSeconds(2));
    }

    @Test
    void contextStampsExpiresAtExactlyOccurredPlusRetention() {
        // Tighter than the bracketed test above: we don't care about
        // wall-clock, we care that expiresAt and occurred are
        // consistent with each other at exactly the configured
        // retention.
        Duration retention = Duration.parse("30d");
        RecordingSupport support = new RecordingSupport(retention);

        RecordContext ctx = support.context(
                Origin.player(),
                Source.player(java.util.UUID.randomUUID(), "Alice"),
                LOC);

        assertThat(ctx.expiresAt())
                .isEqualTo(ctx.occurred().plusSeconds(retention.seconds()));
    }

    @Test
    void differentRetentionsProduceDistinctExpiresAt() {
        // Rotating retention configuration means old records stick
        // around on their old TTL, new records adopt the new TTL.
        // Two support instances with different retentions, same
        // occurred — the derived expiresAt must differ by the
        // retention delta exactly.
        Instant occurred = Instant.parse("2026-04-23T12:00:00Z");
        RecordingSupport oneHour = new RecordingSupport(Duration.parse("1h"));
        RecordingSupport thirtyDays = new RecordingSupport(Duration.parse("30d"));

        long delta = thirtyDays.expiresAt(occurred).getEpochSecond()
                - oneHour.expiresAt(occurred).getEpochSecond();

        long expectedDelta = Duration.parse("30d").seconds() - Duration.parse("1h").seconds();
        assertThat(delta).isEqualTo(expectedDelta);
    }

    @Test
    void contextCarriesOriginSourceLocationUnchanged() {
        // Regression guard: the context builder must pass these three
        // through untouched. If it ever normalizes or swaps them,
        // downstream record factories will persist the wrong source /
        // location and location-scoped queries will silently miss.
        RecordingSupport support = new RecordingSupport(Duration.parse("1d"));
        Origin origin = Origin.environment("lava-fire");
        Source source = Source.entity(java.util.UUID.randomUUID(), "CREEPER");
        BlockLocation location = new BlockLocation(WORLD, "world", 42, 70, -13);

        RecordContext ctx = support.context(origin, source, location);

        assertThat(ctx.origin()).isSameAs(origin);
        assertThat(ctx.source()).isSameAs(source);
        assertThat(ctx.location()).isSameAs(location);
    }
}
