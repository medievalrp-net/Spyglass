package net.medievalrp.spyglass.plugin.importer.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import net.medievalrp.spyglass.plugin.storage.RetentionPolicy;
import org.junit.jupiter.api.Test;

/**
 * {@link MappingContext#expiresAt()} is import-time + retention, clamped to
 * {@link RetentionPolicy#MAX_EXPIRY} (#2) so a keep-forever retention can't
 * push it past ClickHouse's 32-bit {@code DateTime} TTL ceiling (2106),
 * where {@code toDateTime(expires_at)} would overflow to the past and get
 * {@code OPTIMIZE FINAL} to delete the row. worldMap is unused by
 * {@code expiresAt()} so a null is fine here.
 */
class MappingContextTest {

    private static final Instant START = Instant.parse("2026-07-08T00:00:00Z");

    @Test
    void expiresAtIsImportTimePlusRetentionUnderTheCeiling() {
        MappingContext ctx = new MappingContext(null, "srv", Duration.ofDays(182), START);
        assertThat(ctx.expiresAt()).isEqualTo(START.plus(Duration.ofDays(182)));
    }

    @Test
    void neverRetentionClampsToMaxExpiryNotPastTheDateTime32Ceiling() {
        // ~100 years would land in 2126, past DateTime32's 2106 ceiling.
        MappingContext ctx = new MappingContext(
                null, "srv", Duration.ofSeconds(RetentionPolicy.NEVER_SECONDS), START);
        assertThat(ctx.expiresAt()).isEqualTo(RetentionPolicy.MAX_EXPIRY);
        assertThat(ctx.expiresAt()).isBefore(Instant.parse("2106-01-01T00:00:00Z"));
    }
}
