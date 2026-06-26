package net.medievalrp.spyglass.plugin.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** #181: per-event retention resolution and the keep-forever horizon. */
class RetentionPolicyTest {

    private static final Instant T = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void overrideWinsAndOthersInheritTheDefault() {
        RetentionPolicy policy = new RetentionPolicy(3600L, Map.of("break", 100L));
        assertThat(policy.secondsFor("break")).isEqualTo(100L);
        assertThat(policy.secondsFor("place")).isEqualTo(3600L);
        assertThat(policy.defaultSeconds()).isEqualTo(3600L);
    }

    @Test
    void expiresAtIsOccurredPlusTheTypeRetention() {
        RetentionPolicy policy = new RetentionPolicy(3600L, Map.of("say", 60L));
        assertThat(policy.expiresAt(T, "say")).isEqualTo(T.plusSeconds(60L));
        assertThat(policy.expiresAt(T, "break")).isEqualTo(T.plusSeconds(3600L));
    }

    @Test
    void neverIsClampedToTheCeilingUnderClickHousesTtlRange() {
        RetentionPolicy policy = new RetentionPolicy(
                3600L, Map.of("command", RetentionPolicy.NEVER_SECONDS));
        Instant expiry = policy.expiresAt(T, "command");
        // The never horizon is clamped to MAX_EXPIRY, which stays under
        // ClickHouse's toDateTime() ceiling of 2106-02-07.
        assertThat(expiry).isEqualTo(RetentionPolicy.MAX_EXPIRY);
        assertThat(expiry).isBefore(Instant.parse("2106-02-07T00:00:00Z"));
    }

    @Test
    void normalRetentionIsNotClamped() {
        RetentionPolicy policy = new RetentionPolicy(3600L, Map.of("say", 60L));
        assertThat(policy.expiresAt(T, "say")).isEqualTo(T.plusSeconds(60L));
    }

    @Test
    void uniformHasNoOverrides() {
        RetentionPolicy policy = RetentionPolicy.uniform(42L);
        assertThat(policy.overrides()).isEmpty();
        assertThat(policy.secondsFor("anything")).isEqualTo(42L);
        assertThat(policy.defaultSeconds()).isEqualTo(42L);
    }
}
