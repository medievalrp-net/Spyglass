package net.medievalrp.spyglass.plugin.listener.block;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import net.medievalrp.spyglass.api.event.BlockPlaceRecord;
import net.medievalrp.spyglass.api.event.BlockSnapshot;
import net.medievalrp.spyglass.api.event.Origin;
import net.medievalrp.spyglass.api.event.Source;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.plugin.listener.RecordingListener;
import net.medievalrp.spyglass.plugin.listener.RecordingSupport;
import net.medievalrp.spyglass.plugin.pipeline.Recorder;
import net.medievalrp.spyglass.plugin.util.BlockLocations;
import net.medievalrp.spyglass.plugin.util.BlockSnapshots;
import net.medievalrp.spyglass.plugin.util.FallingBlockTracker;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.FallingBlock;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.jetbrains.annotations.ApiStatus;

/**
 * Records the {@code place} event for a falling-block entity that
 * lands and becomes a solid block. Pairs with
 * {@link net.medievalrp.spyglass.plugin.util.FallingBlockCascade},
 * which logs the {@code break} at the original cell and registers it
 * with {@link FallingBlockTracker}; this listener consumes the
 * tracker entry on landing and emits a {@link BlockPlaceRecord} at
 * the landing position attributed to the same player.
 *
 * <p>Without this pairing, a {@code //set 0} over a sand column would
 * roll back to "support restored, original sand restored" — but the
 * sand that fell during the op stayed at the bottom of the column,
 * orphaned. CoreProtect handles this via the same chain (track
 * cascade-broken cells, attribute the landing back to the trigger).
 *
 * <p>We only act on {@code FallingBlock} entities and skip the
 * "block became falling-entity" half of {@link
 * EntityChangeBlockEvent} (which fires with {@code getTo() == AIR});
 * the cascade has already logged that side as a {@code break}, so
 * recording it again here would double-count.
 *
 * <p>Concrete-powder landing in water becomes concrete; the listener
 * records whatever {@code event.getTo()} resolves to, matching the
 * actual end state of the cell.
 */
@ApiStatus.Internal
public final class FallingBlockLandListener implements RecordingListener {

    private final Recorder recorder;
    private final RecordingSupport support;

    public FallingBlockLandListener(Recorder recorder, RecordingSupport support) {
        this.recorder = recorder;
        this.support = support;
    }

    @Override
    public Set<String> events() {
        // Falling-block landings are rendered as "place" so they show
        // up in /sg search a:place and roll back as part of the
        // standard place-undo path. The event-name overlap with direct
        // player places is intentional — same effect on the world,
        // same rollback semantics.
        return Set.of("place");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (!(event.getEntity() instanceof FallingBlock)) {
            return;
        }
        // The "block → falling entity" transition fires with getTo() ==
        // AIR. The cascade already recorded that side as a break;
        // skipping here avoids a duplicate.
        if (event.getTo() == Material.AIR) {
            return;
        }
        Location origin = event.getEntity().getOrigin();
        if (origin == null || origin.getWorld() == null) {
            return;
        }
        Optional<FallingBlockTracker.Tracked> tracked = FallingBlockTracker.consume(
                origin.getWorld().getUID(),
                origin.getBlockX(), origin.getBlockY(), origin.getBlockZ());
        if (tracked.isEmpty()) {
            // Falling block we didn't cascade-log — caused by an
            // explosion, lava burn, or some other non-player path.
            // Out of scope; let it land unrecorded.
            return;
        }

        Block landingBlock = event.getBlock();
        BlockSnapshot before = BlockSnapshots.capture(landingBlock.getState());
        BlockSnapshot after = BlockSnapshots.of(
                event.getTo(), event.getBlockData().getAsString());
        BlockLocation location = BlockLocations.fromLocation(landingBlock.getLocation());

        FallingBlockTracker.Tracked t = tracked.get();
        Origin recordOrigin = Origin.player();
        Source source = Source.player(t.playerId(), t.playerName());
        Instant occurred = support.now();
        recorder.record(new BlockPlaceRecord(
                support.newId(), "place", occurred, support.expiresAt(occurred),
                recordOrigin, source, location, support.serverName(),
                after.material().name(), before, after));
    }
}
