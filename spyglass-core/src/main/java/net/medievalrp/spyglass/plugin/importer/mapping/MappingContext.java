package net.medievalrp.spyglass.plugin.importer.mapping;

import java.time.Duration;
import java.time.Instant;
import net.medievalrp.spyglass.api.event.Origin;
import net.medievalrp.spyglass.plugin.importer.world.WorldMap;
import net.medievalrp.spyglass.plugin.storage.RetentionPolicy;

/**
 * Common state shared by every per-table mapper: world UUID resolver,
 * server name stamp, retention duration, the importStart instant used
 * for {@code expires_at} computation, and the {@link Origin} stamped
 * onto player rows.
 */
public final class MappingContext {

    private final WorldMap worldMap;
    private final String serverName;
    private final Duration retention;
    private final Instant importStart;
    private final Origin importOrigin;

    public MappingContext(WorldMap worldMap, String serverName,
                          Duration retention, Instant importStart) {
        this.worldMap = worldMap;
        this.serverName = serverName == null ? "" : serverName;
        this.retention = retention;
        this.importStart = importStart;
        this.importOrigin = Origin.plugin("coreprotect-import");
    }

    public WorldMap worldMap() { return worldMap; }
    public String serverName() { return serverName; }
    public Duration retention() { return retention; }
    public Instant importStart() { return importStart; }
    public Origin importOrigin() { return importOrigin; }

    /**
     * {@code expires_at} is import-time + retention, NOT event-time + retention.
     * Clamped to {@link RetentionPolicy#MAX_EXPIRY} so a keep-forever ("never")
     * retention can't push the value past ClickHouse's 32-bit {@code DateTime}
     * TTL ceiling (2106), where {@code toDateTime(expires_at)} would overflow to
     * the past and get the row TTL-deleted. Matches the clamp RetentionPolicy
     * already applies on the store side.
     */
    public Instant expiresAt() {
        Instant e = importStart.plus(retention);
        return e.isAfter(RetentionPolicy.MAX_EXPIRY) ? RetentionPolicy.MAX_EXPIRY : e;
    }
}
