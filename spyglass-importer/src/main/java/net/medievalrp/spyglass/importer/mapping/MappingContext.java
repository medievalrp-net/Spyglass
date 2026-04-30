package net.medievalrp.spyglass.importer.mapping;

import java.time.Duration;
import java.time.Instant;
import net.medievalrp.spyglass.api.event.Origin;
import net.medievalrp.spyglass.importer.world.WorldMap;

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

    /** {@code expires_at} is import-time + retention, NOT event-time + retention. */
    public Instant expiresAt() { return importStart.plus(retention); }
}
