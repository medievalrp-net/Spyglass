package net.medievalrp.omniscience2.api.event;

import java.time.Instant;
import java.util.UUID;
import net.medievalrp.omniscience2.api.util.BlockLocation;
import org.jetbrains.annotations.Nullable;

/**
 * A player used a name tag to rename an entity. Captures both the old
 * and new name so rollback is possible in principle (re-naming to the
 * previous value via a scripted NBT edit); the current
 * {@code RollbackEngine} does not yet implement a rename effect.
 *
 * <p>{@code oldName} is null when the entity was previously unnamed.
 */
public record EntityNameRecord(
        UUID id,
        int schemaVersion,
        String event,
        Instant occurred,
        Instant expiresAt,
        Origin origin,
        Source source,
        BlockLocation location,
        String target,
        String entityType,
        UUID entityId,
        @Nullable String oldName,
        String newName) implements EventRecord {

    public static EntityNameRecord of(RecordContext ctx,
                                      String target,
                                      String entityType,
                                      UUID entityId,
                                      @Nullable String oldName,
                                      String newName) {
        return new EntityNameRecord(
                ctx.id(), ctx.schemaVersion(), "named",
                ctx.occurred(), ctx.expiresAt(),
                ctx.origin(), ctx.source(), ctx.location(),
                target, entityType, entityId, oldName, newName);
    }
}
