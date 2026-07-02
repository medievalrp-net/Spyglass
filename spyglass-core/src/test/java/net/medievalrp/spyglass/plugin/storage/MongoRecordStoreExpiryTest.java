package net.medievalrp.spyglass.plugin.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import net.medievalrp.spyglass.api.event.BlockBreakRecord;
import net.medievalrp.spyglass.api.event.BlockSnapshot;
import net.medievalrp.spyglass.api.event.EventRecord;
import net.medievalrp.spyglass.api.event.Origin;
import net.medievalrp.spyglass.api.event.Source;
import net.medievalrp.spyglass.api.util.BlockLocation;
import java.util.Map;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

/**
 * Headless coverage of the Mongo save routing (#206): a record whose stored
 * expiresAt already equals its per-event target takes the streaming typed insert
 * (no BsonDocument tree); only a record needing a different expiry is re-encoded
 * and patched. This pins {@link MongoRecordStore#expiryAlreadyCorrect} without a
 * Mongo connection - the full round-trip is covered by the Testcontainers IT.
 */
class MongoRecordStoreExpiryTest {

    private static final UUID WORLD = UUID.fromString("00000000-0000-0000-0000-0000000000cd");
    private static final Instant OCCURRED = Instant.parse("2026-04-23T12:00:00Z");

    private static BlockBreakRecord record(String event, Instant expiresAt) {
        BlockSnapshot stone = new BlockSnapshot(Material.STONE, "minecraft:stone",
                List.of(), List.of(), List.of(), List.of(), null);
        BlockSnapshot air = new BlockSnapshot(Material.AIR, "minecraft:air",
                List.of(), List.of(), List.of(), List.of(), null);
        return new BlockBreakRecord(UUID.randomUUID(), event, OCCURRED, expiresAt,
                Origin.player(), Source.player(UUID.randomUUID(), "Alice"),
                new BlockLocation(WORLD, "world", 1, 64, 1), "srv", "STONE", stone, air);
    }

    @Test
    void defaultRetentionTypeStampedWithDefaultTakesTheStreamedPath() {
        // "place" has no override, and the record carries occurred + default, so
        // its stored expiresAt already equals the target -> no patch needed.
        RetentionPolicy policy = new RetentionPolicy(3600L, Map.of("break", 100L));
        EventRecord place = record("place", OCCURRED.plusSeconds(3600L));
        assertThat(MongoRecordStore.expiryAlreadyCorrect(place, policy)).isTrue();
    }

    @Test
    void overriddenTypeStampedWithDefaultNeedsThePatch() {
        // "break" is overridden to 100s but the record was stamped with the 3600s
        // global default, so the target differs -> must be re-encoded and patched.
        RetentionPolicy policy = new RetentionPolicy(3600L, Map.of("break", 100L));
        EventRecord brk = record("break", OCCURRED.plusSeconds(3600L));
        assertThat(MongoRecordStore.expiryAlreadyCorrect(brk, policy)).isFalse();
    }

    @Test
    void overriddenTypeAlreadyStampedWithItsOverrideTakesTheStreamedPath() {
        // If a record already carries its override expiry, no patch is needed.
        RetentionPolicy policy = new RetentionPolicy(3600L, Map.of("break", 100L));
        EventRecord brk = record("break", OCCURRED.plusSeconds(100L));
        assertThat(MongoRecordStore.expiryAlreadyCorrect(brk, policy)).isTrue();
    }

    @Test
    void nullExpiresAtNeedsThePatch() {
        RetentionPolicy policy = RetentionPolicy.uniform(3600L);
        EventRecord noExpiry = record("place", null);
        assertThat(MongoRecordStore.expiryAlreadyCorrect(noExpiry, policy)).isFalse();
    }

    @Test
    void neverRetentionClampsToTheCeilingSoAStampedDefaultNeedsThePatch() {
        // "say" kept forever: target is the clamped MAX_EXPIRY, not occurred +
        // default, so a default-stamped record is patched up to the ceiling.
        RetentionPolicy policy = new RetentionPolicy(3600L,
                Map.of("say", RetentionPolicy.NEVER_SECONDS));
        EventRecord say = record("say", OCCURRED.plusSeconds(3600L));
        assertThat(MongoRecordStore.expiryAlreadyCorrect(say, policy)).isFalse();

        EventRecord alreadyNever = record("say", RetentionPolicy.MAX_EXPIRY);
        assertThat(MongoRecordStore.expiryAlreadyCorrect(alreadyNever, policy)).isTrue();
    }
}
