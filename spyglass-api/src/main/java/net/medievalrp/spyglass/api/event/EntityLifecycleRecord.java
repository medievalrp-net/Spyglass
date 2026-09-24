package net.medievalrp.spyglass.api.event;

import java.time.Instant;
import java.util.UUID;
import net.medievalrp.spyglass.api.rollback.Rollbackable;
import net.medievalrp.spyglass.api.rollback.RollbackEffect;
import net.medievalrp.spyglass.api.util.BlockLocation;

/** Placement/removal of a nonliving decoration, retaining the complete serialized entity. */
public record EntityLifecycleRecord(UUID id, String event, Instant occurred, Instant expiresAt,
        Origin origin, Source source, BlockLocation location, String server, String target,
        String entityType, UUID entityId, String entityNbt) implements EventRecord, Rollbackable {
    public static EntityLifecycleRecord of(RecordContext ctx, String event, String target,
            String type, UUID entityId, String nbt) {
        return new EntityLifecycleRecord(ctx.id(), event, ctx.occurred(), ctx.expiresAt(),
                ctx.origin(), ctx.source(), ctx.location(), ctx.server(), target, type, entityId, nbt);
    }
    public static RollbackEffect effect(boolean rollback, String event, BlockLocation location,
            String type, UUID id, String nbt) {
        boolean spawn = rollback != event.equals("cushion-place");
        if (id == null || (spawn && (nbt == null || nbt.isBlank()))) return null;
        return spawn ? new RollbackEffect.EntitySpawn(location, type, nbt, id.toString())
                : new RollbackEffect.EntityRemove(location, type, id.toString());
    }
    @Override public RollbackEffect rollbackEffect() {
        return effect(true, event, location, entityType, entityId, entityNbt);
    }
    @Override public RollbackEffect restoreEffect() {
        return effect(false, event, location, entityType, entityId, entityNbt);
    }
}
