package net.medievalrp.spyglass.plugin.worldedit;

import java.time.Instant;
import java.util.List;
import net.medievalrp.spyglass.api.event.BlockBreakRecord;
import net.medievalrp.spyglass.api.event.BlockPlaceRecord;
import net.medievalrp.spyglass.api.event.BlockSnapshot;
import net.medievalrp.spyglass.api.event.EventRecord;
import net.medievalrp.spyglass.api.event.Origin;
import net.medievalrp.spyglass.api.event.Source;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.plugin.listener.RecordingSupport;
import org.jetbrains.annotations.ApiStatus;

/**
 * Turns a resolved before/after pair for one WorldEdit-edited cell into the
 * break / place records it should emit. Pulled out of {@link WorldEditSubscriber}
 * so the gating — which never runs on the main thread — is unit-testable without
 * a live WorldEdit platform (the {@code BaseBlock} -> {@link BlockSnapshot}
 * conversion that needs one stays in the subscriber).
 *
 * <p>The rules mirror the long-standing listener behavior: a non-air before is a
 * break, a non-air after is a place, and a block changing from one solid to
 * another emits both. Air-to-air (a no-op overwrite) emits nothing.
 */
@ApiStatus.Internal
final class WorldEditRecords {

    private WorldEditRecords() {
    }

    /**
     * Append the break and/or place records for one cell to {@code out}.
     *
     * @param before snapshot of the block before the edit (air if none)
     * @param after  snapshot of the block the edit set (air if cleared)
     */
    static void appendCell(List<EventRecord> out, RecordingSupport support, Origin origin,
                           Source source, String serverName, BlockLocation location,
                           Instant occurred, BlockSnapshot before, BlockSnapshot after) {
        Instant expires = support.expiresAt(occurred);
        if (!before.isAir()) {
            out.add(new BlockBreakRecord(support.newId(), "break", occurred, expires,
                    origin, source, location, serverName,
                    before.material().name(), before, after));
        }
        if (!after.isAir()) {
            out.add(new BlockPlaceRecord(support.newId(), "place", occurred, expires,
                    origin, source, location, serverName,
                    after.material().name(), before, after));
        }
    }
}
