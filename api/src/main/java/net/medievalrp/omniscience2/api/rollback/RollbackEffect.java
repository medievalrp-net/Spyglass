package net.medievalrp.omniscience2.api.rollback;

import net.medievalrp.omniscience2.api.event.BlockSnapshot;
import net.medievalrp.omniscience2.api.event.StoredItem;
import net.medievalrp.omniscience2.api.util.BlockLocation;

public sealed interface RollbackEffect permits
        RollbackEffect.BlockReplace,
        RollbackEffect.ContainerSlotWrite,
        RollbackEffect.EntitySpawn,
        RollbackEffect.EntityRemove {

    record BlockReplace(BlockLocation location, BlockSnapshot expectedCurrent, BlockSnapshot replacement) implements RollbackEffect {
    }

    record ContainerSlotWrite(BlockLocation location, int slot, StoredItem expectedCurrent, StoredItem replacement) implements RollbackEffect {
    }

    record EntitySpawn(BlockLocation location, String entityType, String serializedEntity) implements RollbackEffect {
    }

    record EntityRemove(BlockLocation location, String entityType, String entityId) implements RollbackEffect {
    }
}
