package net.medievalrp.spyglass.plugin.snapshot;

import java.util.logging.Logger;
import net.medievalrp.spyglass.api.SpyglassApi;
import net.medievalrp.spyglass.api.event.ItemPickupRecord;
import net.medievalrp.spyglass.api.event.RecordContext;
import net.medievalrp.spyglass.api.event.StoredItem;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.plugin.listener.RecordingSupport;
import net.medievalrp.spyglass.plugin.util.BlockLocations;
import net.medievalrp.spyglass.plugin.util.ItemSerialization;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;

/**
 * Records an operator taking a copy of an item out of a {@code /sg snapshot}
 * view (a past-instant player inventory or container), so every take is
 * auditable ({@code a:snapshot-take}). Same shape as {@code
 * SalvageWithdrawLogger}: the GUI and the text-fallback {@code take} command
 * both call {@link #log} after the copy already landed in the taker's
 * inventory - this only builds the audit record, it never touches inventory
 * contents itself.
 *
 * <p>Reuses {@link ItemPickupRecord} (the {@code salvage-withdraw}
 * precedent, #76), so both storage backends already persist it - the
 * {@code extensions} channel this event needs was added to the record
 * itself (#341) rather than costing a schema change. The item field is the
 * forensic-only projection ({@link ItemSerialization#storedItemProjection}):
 * a take is never rolled back or replayed, so the base64 blob would be dead
 * weight (#103).
 *
 * <p>Goes through {@link SpyglassApi#record} rather than the internal
 * {@code Recorder} directly - that call rejects a null location at the
 * boundary (#230) instead of failing silently downstream, and a taker's
 * location is always real (never a global/sentinel event).
 */
@ApiStatus.Internal
public final class SnapshotTakeLogger {

    private final SpyglassApi api;
    private final RecordingSupport support;
    private final Logger logger;

    public SnapshotTakeLogger(SpyglassApi api, RecordingSupport support, Logger logger) {
        this.api = api;
        this.support = support;
        this.logger = logger;
    }

    /**
     * Log one take. {@code slot} is the source slot in the session's view
     * (a player-inventory index or a container slot), carried into the
     * stored item projection so the audit row names where the copy came
     * from. Never throws - a logging failure must not surface as a failed
     * take, since the copy has already been placed in the taker's
     * inventory by the time this runs.
     */
    public void log(Player taker, SnapshotSession session, ItemStack takenStack, int slot) {
        if (takenStack == null || takenStack.getType() == Material.AIR) {
            return;
        }
        try {
            StoredItem projection = ItemSerialization.storedItemProjection(slot, takenStack);
            if (projection == null) {
                return;
            }
            BlockLocation location = BlockLocations.fromLocation(taker.getLocation());
            RecordContext ctx = support.playerContext(taker, location)
                    .withExtension("snapshot-subject", session.subjectLabel())
                    .withExtension("snapshot-at", session.asOf().toString());
            api.record(ItemPickupRecord.of(ctx, "snapshot-take",
                    takenStack.getType().name(), takenStack.getAmount(), projection));
        } catch (RuntimeException ex) {
            logger.warning("Spyglass snapshot take log failed: " + ex.getMessage());
        }
    }
}
