package net.medievalrp.spyglass.api.event;

import java.time.Instant;
import java.util.UUID;
import net.medievalrp.spyglass.api.util.BlockLocation;

public record EntityHitRecord(
        UUID id,
        String event,
        Instant occurred,
        Instant expiresAt,
        Origin origin,
        Source source,
        BlockLocation location,
        String target,
        String victimType,
        UUID victimId,
        double damage,
        boolean projectile,
        String projectileType) implements EventRecord {

    public static EntityHitRecord of(RecordContext ctx, String event, String target,
                                     String victimType, UUID victimId,
                                     double damage, boolean projectile, String projectileType) {
        return new EntityHitRecord(
                ctx.id(), event, ctx.occurred(), ctx.expiresAt(),
                ctx.origin(), ctx.source(), ctx.location(),
                target, victimType, victimId, damage, projectile, projectileType);
    }
}
