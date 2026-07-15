package net.medievalrp.spyglass.api.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import net.medievalrp.spyglass.api.rollback.RollbackEffect;
import net.medievalrp.spyglass.api.util.BlockLocation;
import org.junit.jupiter.api.Test;

/**
 * #284: only a player kill is resurrectable. Environment deaths (a zombie
 * burning at dawn) decline both effect directions - they stay searchable
 * but never come back on a rollback, and never re-remove on a restore.
 */
class EntityDeathRecordTest {

    private static EntityDeathRecord death(String killerType) {
        Instant now = Instant.now();
        return new EntityDeathRecord(UUID.randomUUID(), "death", now, now.plusSeconds(60),
                Origin.player(), Source.player(UUID.randomUUID(), "Alice"),
                new BlockLocation(UUID.randomUUID(), "world", 1, 64, 2),
                "srv", "SHEEP", "sheep", UUID.randomUUID(), killerType, "ENTITY_ATTACK", null);
    }

    @Test
    void playerKillsProduceEffectsInBothDirections() {
        EntityDeathRecord record = death("player");
        assertThat(record.resurrectable()).isTrue();
        assertThat(record.rollbackEffect()).isInstanceOf(RollbackEffect.EntitySpawn.class);
        assertThat(record.restoreEffect()).isInstanceOf(RollbackEffect.EntityRemove.class);
    }

    @Test
    void environmentDeathsDeclineBothDirections() {
        for (String killer : new String[]{"FIRE_TICK", "LAVA", "SUFFOCATION", null}) {
            EntityDeathRecord record = death(killer);
            assertThat(record.resurrectable()).as("killer=" + killer).isFalse();
            assertThat(record.rollbackEffect()).as("killer=" + killer).isNull();
            assertThat(record.restoreEffect()).as("killer=" + killer).isNull();
        }
    }

    // CoreProtect-imported kill rows carry the namespaced killer form; they
    // are player kills and must stay resurrectable, or an import's history
    // silently loses entity coverage on rollback.
    @Test
    void namespacedPlayerKillerCountsAsAPlayerKill() {
        EntityDeathRecord record = death("minecraft:player");
        assertThat(record.resurrectable()).isTrue();
        assertThat(record.rollbackEffect()).isInstanceOf(RollbackEffect.EntitySpawn.class);
    }

    @Test
    void isPlayerKillNormalizesTheNamespaceOnly() {
        assertThat(EntityDeathRecord.isPlayerKill("player")).isTrue();
        assertThat(EntityDeathRecord.isPlayerKill("PLAYER")).isTrue();
        assertThat(EntityDeathRecord.isPlayerKill("minecraft:player")).isTrue();
        assertThat(EntityDeathRecord.isPlayerKill("minecraft:zombie")).isFalse();
        assertThat(EntityDeathRecord.isPlayerKill("player_head")).isFalse();
        assertThat(EntityDeathRecord.isPlayerKill(null)).isFalse();
    }
}
