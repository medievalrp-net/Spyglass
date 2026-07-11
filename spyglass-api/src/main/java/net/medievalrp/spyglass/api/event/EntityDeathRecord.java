package net.medievalrp.spyglass.api.event;

import java.time.Instant;
import java.util.UUID;
import net.medievalrp.spyglass.api.rollback.RollbackEffect;
import net.medievalrp.spyglass.api.rollback.Rollbackable;
import net.medievalrp.spyglass.api.util.BlockLocation;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Death record. Rollback re-spawns the entity from its captured NBT when a
 * snapshot exists, falling back to a fresh spawn of {@link #entityType()}
 * otherwise (#29 — Paper refuses to serialize dying entities, so most death
 * records carry no NBT). NBT, when present, is version-brittle across
 * Minecraft releases; a failed deserialize also falls back to by-type.
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

    /**
     * Only a player kill is worth resurrecting (#284): environment deaths
     * (FIRE_TICK, LAVA, SUFFOCATION, ...) resurrected en masse on any
     * generic area rollback - one fresh-world night left 453 burned
     * zombies waiting to come back. The Mongo and ClickHouse emitEffect
     * mirrors apply this same rule from their killer column; keep them in
     * lockstep by calling {@link #isPlayerKill}.
     */
    public boolean resurrectable() {
        return isPlayerKill(killerType);
    }

    /**
     * The killer-gate predicate shared with the lean store paths. Live
     * listeners write the bare form ({@code "player"}); CoreProtect-imported
     * rows carry the namespaced form ({@code "minecraft:player"}) - both are
     * player kills, so both must pass or imported history silently stops
     * being resurrectable.
     */
    public static boolean isPlayerKill(String killerType) {
        if (killerType == null) {
            return false;
        }
        int namespace = killerType.lastIndexOf(':');
        String bare = namespace >= 0 ? killerType.substring(namespace + 1) : killerType;
        return "player".equalsIgnoreCase(bare);
    }

    @Override
    public RollbackEffect rollbackEffect() {
        if (!resurrectable()) {
            return null;
        }
        return new RollbackEffect.EntitySpawn(location, entityType, entityNbt,
                entityId == null ? null : entityId.toString());
    }

    @Override
    public RollbackEffect restoreEffect() {
        if (!resurrectable()) {
            return null;
        }
        return new RollbackEffect.EntityRemove(location, entityType,
                entityId == null ? null : entityId.toString());
    }
}
