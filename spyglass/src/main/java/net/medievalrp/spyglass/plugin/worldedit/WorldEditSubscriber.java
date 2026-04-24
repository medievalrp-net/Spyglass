package net.medievalrp.spyglass.plugin.worldedit;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.event.extent.EditSessionEvent;
import com.sk89q.worldedit.extent.AbstractDelegateExtent;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.extension.platform.Actor;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.util.eventbus.Subscribe;
import com.sk89q.worldedit.world.block.BlockStateHolder;
import java.time.Instant;
import java.util.logging.Logger;
import net.medievalrp.spyglass.api.event.BlockBreakRecord;
import net.medievalrp.spyglass.api.event.BlockPlaceRecord;
import net.medievalrp.spyglass.api.event.BlockSnapshot;
import net.medievalrp.spyglass.api.event.Origin;
import net.medievalrp.spyglass.api.event.Source;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.plugin.listener.RecordingSupport;
import net.medievalrp.spyglass.plugin.pipeline.Recorder;
import net.medievalrp.spyglass.plugin.util.BlockLocations;
import net.medievalrp.spyglass.plugin.util.BlockSnapshots;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;

/**
 * Subscribes to WorldEdit's EditSessionEvent and wraps the extent chain with a
 * logger. Every setBlock on the wrapped extent produces a break + place record
 * tagged with Origin.worldEdit() before delegating.
 *
 * <p>This path handles vanilla WorldEdit. FastAsyncWorldEdit's fast-placement
 * mode bypasses the extent chain and is wired separately (deferred).
 */
@ApiStatus.Internal
public final class WorldEditSubscriber {

    private final Recorder recorder;
    private final RecordingSupport support;
    private final Logger logger;

    public WorldEditSubscriber(Recorder recorder, RecordingSupport support, Logger logger) {
        this.recorder = recorder;
        this.support = support;
        this.logger = logger;
    }

    public void register() {
        WorldEdit.getInstance().getEventBus().register(this);
        logger.info("Spyglass: WorldEdit logging subscriber registered.");
    }

    public void unregister() {
        WorldEdit.getInstance().getEventBus().unregister(this);
    }

    @Subscribe
    public void onEditSession(EditSessionEvent event) {
        if (event.getStage() != EditSession.Stage.BEFORE_CHANGE) {
            return;
        }
        Actor actor = event.getActor();
        if (actor == null || !actor.isPlayer()) {
            return;
        }
        Player bukkitPlayer = Bukkit.getPlayer(actor.getUniqueId());
        if (bukkitPlayer == null) {
            return;
        }
        World world = BukkitAdapter.adapt(event.getWorld());
        if (world == null) {
            return;
        }
        if (isFawePresent()) {
            try {
                if (FaweHook.tryInstall(recorder, support, event, bukkitPlayer, world)) {
                    return;
                }
            } catch (Throwable thrown) {
                logger.warning("Spyglass: FAWE hook failed: " + thrown);
            }
        }
        event.setExtent(new LoggingExtent(event.getExtent(), bukkitPlayer, world));
    }

    private boolean isFawePresent() {
        return Bukkit.getPluginManager().getPlugin("FastAsyncWorldEdit") != null;
    }

    private final class LoggingExtent extends AbstractDelegateExtent {
        private final Player player;
        private final World world;

        LoggingExtent(Extent extent, Player player, World world) {
            super(extent);
            this.player = player;
            this.world = world;
        }

        @Override
        public <B extends BlockStateHolder<B>> boolean setBlock(BlockVector3 position, B block)
                throws com.sk89q.worldedit.WorldEditException {
            Location bukkitLocation = new Location(world, position.x(), position.y(), position.z());
            BlockSnapshot original = BlockSnapshots.capture(bukkitLocation.getBlock().getState());
            boolean originalIsAir = original.isAir();
            BlockLocation location = BlockLocations.fromLocation(bukkitLocation);
            Instant occurred = support.now();
            Origin origin = Origin.worldEdit();
            Source source = Source.player(player.getUniqueId(), player.getName());

            boolean result = super.setBlock(position, block);

            BlockSnapshot after = BlockSnapshots.capture(bukkitLocation.getBlock().getState());
            boolean newIsAir = after.isAir();

            if (!originalIsAir) {
                recorder.record(new BlockBreakRecord(
                        support.newId(), 1, "break", occurred, support.expiresAt(occurred),
                        origin, source, location,
                        original.material().name(), original, after));
            }
            if (!newIsAir) {
                recorder.record(new BlockPlaceRecord(
                        support.newId(), 1, "place", occurred, support.expiresAt(occurred),
                        origin, source, location,
                        after.material().name(), original, after));
            }
            return result;
        }
    }
}
