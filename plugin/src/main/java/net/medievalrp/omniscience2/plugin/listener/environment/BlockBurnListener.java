package net.medievalrp.omniscience2.plugin.listener.environment;

import java.util.Set;
import net.medievalrp.omniscience2.api.event.BlockBreakRecord;
import net.medievalrp.omniscience2.api.event.BlockSnapshot;
import net.medievalrp.omniscience2.api.event.Origin;
import net.medievalrp.omniscience2.api.event.RecordContext;
import net.medievalrp.omniscience2.api.event.Source;
import net.medievalrp.omniscience2.api.util.BlockLocation;
import net.medievalrp.omniscience2.plugin.listener.RecordingListener;
import net.medievalrp.omniscience2.plugin.listener.RecordingSupport;
import net.medievalrp.omniscience2.plugin.pipeline.Recorder;
import net.medievalrp.omniscience2.plugin.util.BlockLocations;
import net.medievalrp.omniscience2.plugin.util.BlockSnapshots;
import net.medievalrp.omniscience2.plugin.util.PlayerSourceMetadata;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.ApiStatus;

/**
 * Logs blocks destroyed by fire. If the igniting fire block was tagged
 * via {@link PlayerSourceMetadata} (which happens when a player lit the
 * original fire), the burn is attributed back to that player instead of
 * generic environment. This is the downstream half of the ignite-chain
 * propagation started in {@link BlockIgniteListener}.
 *
 * <p>v1 did not attribute burns — it always logged them as environment.
 * This is a v2 improvement.
 */
@ApiStatus.Internal
public final class BlockBurnListener implements RecordingListener {

    private final Recorder recorder;
    private final RecordingSupport support;
    private final JavaPlugin plugin;

    public BlockBurnListener(Recorder recorder, RecordingSupport support, JavaPlugin plugin) {
        this.recorder = recorder;
        this.support = support;
        this.plugin = plugin;
    }

    @Override
    public Set<String> events() {
        return Set.of("break");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBurn(BlockBurnEvent event) {
        BlockSnapshot original = BlockSnapshots.capture(event.getBlock().getState());
        BlockSnapshot after = BlockSnapshots.air();
        BlockLocation location = BlockLocations.fromLocation(event.getBlock().getLocation());

        Origin origin;
        Source source;
        if (event.getIgnitingBlock() != null) {
            PlayerSourceMetadata.Attribution attribution =
                    PlayerSourceMetadata.attributionOf(event.getIgnitingBlock(), plugin);
            if (attribution.isPresent()) {
                origin = support.environmentOrigin("fire-spread");
                source = Source.player(attribution.id(), attribution.name());
            } else {
                origin = support.environmentOrigin("burn");
                source = support.environmentSource("fire");
            }
        } else {
            origin = support.environmentOrigin("burn");
            source = support.environmentSource("fire");
        }

        RecordContext ctx = support.context(origin, source, location);
        recorder.record(BlockBreakRecord.of(ctx, "break", original.material().name(), original, after));
    }
}
