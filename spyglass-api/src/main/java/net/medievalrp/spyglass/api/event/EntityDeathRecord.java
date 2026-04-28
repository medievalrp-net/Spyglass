package net.medievalrp.spyglass.api.event;

import java.time.Instant;
import java.util.UUID;
import net.medievalrp.spyglass.api.rollback.RollbackEffect;
import net.medievalrp.spyglass.api.rollback.Rollbackable;
import net.medievalrp.spyglass.api.util.BlockLocation;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Death record. Rollback re-spawns the entity from its captured NBT; note that
 * NBT schema is version-brittle across Minecraft releases, so rollback across
 * a server upgrade may fail silently.
 */
public record EntityDeathRecord(
        UUID id,
        String event,
        Instant occurred,
        Instant expiresAt,
        Origin origin,
        Source source,
        BlockLocation location,
        String server,
        String target,
        String entityType,
        UUID entityId,
        String killerType,
        String damageCause,
        @Nullable @ApiStatus.Experimental String entityNbt) implements EventRecord, Rollbackable {

    public static EntityDeathRecord of(RecordContext ctx, String target,
                                       String entityType, UUID entityId,
                                       String killerType, String damageCause,
                                       @Nullable String entityNbt) {
        return new EntityDeathRecord(
                ctx.id(), "death", ctx.occurred(), ctx.expiresAt(),
                ctx.origin(), ctx.source(), ctx.location(), ctx.server(),
                target, entityType, entityId, killerType, damageCause, entityNbt);
    }

    @Override
    public RollbackEffect rollbackEffect() {
        return new RollbackEffect.EntitySpawn(location, entityType, entityNbt);
    }

    @Override
    public RollbackEffect restoreEffect() {
        return new RollbackEffect.EntityRemove(location, entityType,
                entityId == null ? null : entityId.toString());
    }
}
