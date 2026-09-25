package net.medievalrp.spyglass.api.event;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.UUID;
import net.medievalrp.spyglass.api.rollback.RollbackEffect;
import org.junit.jupiter.api.Test;

class EntityLifecycleRecordTest {
    @Test void placementAndRemovalHaveOppositeEffects() {
        UUID id = UUID.randomUUID();
        assertThat(EntityLifecycleRecord.effect(true, "cushion-place", null, "cushion", id, "nbt"))
                .isInstanceOf(RollbackEffect.EntityRemove.class);
        assertThat(EntityLifecycleRecord.effect(false, "cushion-place", null, "cushion", id, "nbt"))
                .isEqualTo(new RollbackEffect.EntitySpawn(null, "cushion", "nbt", id.toString()));
        assertThat(EntityLifecycleRecord.effect(true, "cushion-break", null, "cushion", id, "nbt"))
                .isInstanceOf(RollbackEffect.EntitySpawn.class);
        assertThat(EntityLifecycleRecord.effect(false, "cushion-break", null, "cushion", id, "nbt"))
                .isInstanceOf(RollbackEffect.EntityRemove.class);
    }
    @Test void missingSnapshotNeverSpawnsADefaultDecoration() {
        assertThat(EntityLifecycleRecord.effect(true, "cushion-break", null, "cushion", UUID.randomUUID(), null)).isNull();
    }
}
