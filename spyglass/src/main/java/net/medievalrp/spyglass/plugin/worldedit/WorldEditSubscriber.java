package net.medievalrp.spyglass.plugin.worldedit;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.event.extent.EditSessionEvent;
import com.sk89q.worldedit.extent.AbstractDelegateExtent;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.extension.platform.Actor;
import com.sk89q.worldedit.function.operation.Operation;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.util.eventbus.Subscribe;
import com.sk89q.worldedit.world.block.BaseBlock;
import com.sk89q.worldedit.world.block.BlockStateHolder;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.logging.Logger;
import net.medievalrp.spyglass.api.event.BlockSnapshot;
import net.medievalrp.spyglass.api.event.EventRecord;
import net.medievalrp.spyglass.api.event.Origin;
import net.medievalrp.spyglass.api.event.Source;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.plugin.listener.RecordingSupport;
import net.medievalrp.spyglass.plugin.pipeline.Recorder;
import net.medievalrp.spyglass.plugin.util.BlockSnapshots;
import net.medievalrp.spyglass.plugin.util.FallingBlockCascade;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.ApiStatus;

/**
 * Subscribes to WorldEdit's EditSessionEvent and wraps the extent chain with a
 * logger. Every setBlock on the wrapped extent yields a break + place record
 * tagged with Origin.worldEdit().
 *
 * <p><b>Off-main design.</b> Vanilla WorldEdit runs the whole edit on the main
 * thread, so the one thing we cannot move is reading the <em>before</em> block —
 * we have to read it on the thread that is about to overwrite it. Everything
 * else does: {@link LoggingExtent#setBlock} captures only WorldEdit's own
 * immutable block objects ({@code getFullBlock} for the before, the {@code block}
 * parameter for the after — no second world read) into a per-session buffer, and
 * the heavy work (block-data string building, {@link BlockSnapshot} allocation,
 * record construction, {@code recorder.recordAll}) runs off-main when the edit
 * flushes ({@link LoggingExtent#commitBefore}) or when the buffer fills. This is
 * the same shape as the FAWE path ({@link FaweBatchLogger}), which already builds
 * records off the main thread from WorldEdit's chunk data.
 *
 * <p>Tile entities (containers, signs, banners, …) are the exception: their live
 * inventory/text is only safe to read on the main thread, so those — detected by
 * a non-null block-entity NBT — are captured in full via {@link
 * BlockSnapshots#capture} inline. They are rare in a bulk edit's block volume.
 *
 * <p>FastAsyncWorldEdit's fast-placement mode bypasses the extent chain and is
 * wired separately ({@link FaweHook}).
 */
@ApiStatus.Internal
public final class WorldEditSubscriber {

    private final Recorder recorder;
    private final RecordingSupport support;
    private final Executor asyncExecutor;
    private final Plugin plugin;
    private final Logger logger;

    public WorldEditSubscriber(Recorder recorder, RecordingSupport support,
                               Executor asyncExecutor, Plugin plugin, Logger logger) {
        this.recorder = recorder;
        this.support = support;
        this.asyncExecutor = asyncExecutor;
        this.plugin = plugin;
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

    /** One buffered cell: the immutable inputs a record needs, captured on the
     *  main thread and converted into records off it. For each side exactly one
     *  of the {@code rich*} snapshot (tile entity, captured in full on main) or
     *  the {@code *We} block (plain block, converted off-main) is non-null. */
    private record Pending(int x, int y, int z, Instant occurred,
                           BlockSnapshot richBefore, BaseBlock beforeWe,
                           BlockSnapshot richAfter, BaseBlock afterWe) {
    }

    private final class LoggingExtent extends AbstractDelegateExtent {

        /** Hand the buffer off-main once it reaches this many cells, so a
         *  multi-million-block edit neither holds every cell in memory nor
         *  waits for the whole op to finish before any record is built. */
        private static final int DRAIN_THRESHOLD = 50_000;

        private final Player player;
        private final World world;
        private final UUID playerId;
        private final String playerName;
        private final UUID worldId;
        private final String worldName;

        // Per-EditSession dedup for the falling-block cascade emit. Without
        // this, if WE breaks every cell of an N-tall sand column in the same
        // op, each break re-walks the column above and re-logs every
        // still-standing sand cell — O(N²) audit events for a single //set 0
        // over a sand silo.
        private final Set<Long> cascadedAbove = new HashSet<>();
        private List<Pending> buffer = new ArrayList<>();
        // A next-tick drain is queued. Coalesces a whole edit's worth of
        // setBlock calls (vanilla WE runs them synchronously within the tick)
        // into a single off-main build, without depending on WorldEdit calling
        // commit() on this particular extent.
        private boolean drainScheduled = false;

        LoggingExtent(Extent extent, Player player, World world) {
            super(extent);
            this.player = player;
            this.world = world;
            this.playerId = player.getUniqueId();
            this.playerName = player.getName();
            this.worldId = world.getUID();
            this.worldName = world.getName();
        }

        @Override
        public <B extends BlockStateHolder<B>> boolean setBlock(BlockVector3 position, B block)
                throws com.sk89q.worldedit.WorldEditException {
            int x = position.x();
            int y = position.y();
            int z = position.z();

            // The before-state: WorldEdit's own immutable read, NBT included.
            // Cheaper than a Bukkit getState() and — being immutable — safe to
            // convert into a snapshot off the main thread.
            BaseBlock weBefore = super.getFullBlock(position);
            boolean beforeTile = isTileEntity(weBefore);
            // Tile entities carry inventory / sign text / banner patterns that
            // are only safe to read on the main thread; capture them in full now.
            // The before is what a rollback restores, so it must stay faithful.
            BlockSnapshot richBefore = beforeTile
                    ? BlockSnapshots.capture(world.getBlockAt(x, y, z).getState()) : null;
            // The after-state is exactly what we are setting — for plain blocks
            // no second world read is needed (the block parameter is the after).
            BaseBlock weAfter = block.toBaseBlock();
            boolean afterTile = isTileEntity(weAfter);
            Instant occurred = support.now();

            boolean result = super.setBlock(position, block);

            // Only a placed tile entity needs a post-write read — to keep the
            // after faithful for /sg restore (redo). Plain blocks (the bulk of
            // any edit) never hit this; their after is built off-main from the
            // parameter.
            BlockSnapshot richAfter = afterTile
                    ? BlockSnapshots.capture(world.getBlockAt(x, y, z).getState()) : null;

            buffer.add(new Pending(x, y, z, occurred,
                    richBefore, beforeTile ? null : weBefore,
                    richAfter, afterTile ? null : weAfter));
            if (buffer.size() >= DRAIN_THRESHOLD) {
                drain();
            } else {
                scheduleDrain();
            }

            // Falling-block cascade stays on the main thread: it walks the live
            // column above a break-to-air, which is rare relative to the bulk
            // per-block path and inherently a main-thread world read.
            boolean beforeAir = !beforeTile && weBefore.getBlockType().getMaterial().isAir();
            boolean afterAir = !afterTile && weAfter.getBlockType().getMaterial().isAir();
            if (!beforeAir && afterAir) {
                FallingBlockCascade.emitCascadeAbove(recorder, support, player, world,
                        x, y, z, cascadedAbove);
            }
            return result;
        }

        @Override
        protected Operation commitBefore() {
            // Belt-and-suspenders: drain if WorldEdit happens to commit this
            // extent. The scheduled next-tick drain is the primary trigger,
            // since not every WE version routes commit() through here.
            drain();
            return super.commitBefore();
        }

        /** Queue a one-tick-later drain (idempotent within a tick). Runs on the
         *  main thread, so all of this edit's buffered cells are present before
         *  it fires. */
        private void scheduleDrain() {
            if (drainScheduled) {
                return;
            }
            drainScheduled = true;
            try {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    drainScheduled = false;
                    drain();
                });
            } catch (IllegalPluginAccessException disabling) {
                // Plugin disabling — scheduler refuses new tasks. Drain now.
                drainScheduled = false;
                drain();
            }
        }

        /** Hand the current buffer to the async executor and start a fresh one.
         *  If the executor has been shut down (server stopping), build inline so
         *  no records are dropped. */
        private void drain() {
            if (buffer.isEmpty()) {
                return;
            }
            List<Pending> batch = buffer;
            buffer = new ArrayList<>();
            try {
                asyncExecutor.execute(() -> build(batch));
            } catch (RuntimeException rejected) {
                build(batch);
            }
        }

        /** Off-main: convert WorldEdit's captured block data into break / place
         *  records and queue them. Touches only immutable inputs plus
         *  thread-safe helpers ({@code BukkitAdapter.adapt}, {@code support.*},
         *  {@code recorder.recordAll}). */
        private void build(List<Pending> batch) {
            try {
                Origin origin = Origin.worldEdit();
                Source source = Source.player(playerId, playerName);
                String serverName = support.serverName();
                List<EventRecord> out = new ArrayList<>(batch.size() * 2);
                for (Pending p : batch) {
                    BlockSnapshot before = p.richBefore() != null ? p.richBefore() : snapshot(p.beforeWe());
                    BlockSnapshot after = p.richAfter() != null ? p.richAfter() : snapshot(p.afterWe());
                    BlockLocation location = new BlockLocation(worldId, worldName, p.x(), p.y(), p.z());
                    WorldEditRecords.appendCell(out, support, origin, source, serverName,
                            location, p.occurred(), before, after);
                }
                if (!out.isEmpty()) {
                    recorder.recordAll(out);
                }
            } catch (RuntimeException ex) {
                // Never let an off-main build die silently — a swallowed
                // exception here would drop a whole edit's audit trail.
                logger.warning("Spyglass: WorldEdit off-main record build failed ("
                        + batch.size() + " cells): " + ex);
            }
        }

        /** A block WorldEdit reports as carrying block-entity NBT (or whose
         *  material is a container) — chest, sign, banner, jukebox, decorated
         *  pot, … — whose extra data BlockSnapshots.capture only reads safely on
         *  the main thread. */
        private static boolean isTileEntity(BaseBlock block) {
            return block.getNbtReference() != null
                    || block.getBlockType().getMaterial().hasContainer();
        }

        /** WorldEdit block -> Spyglass snapshot (material + block-data string).
         *  No inventory / sign data: tile entities take the {@code rich*}
         *  path instead. Pure data conversion, safe off the main thread. */
        private BlockSnapshot snapshot(BaseBlock we) {
            if (we == null || we.getBlockType().getMaterial().isAir()) {
                return BlockSnapshots.air();
            }
            BlockData data = BukkitAdapter.adapt(we.toImmutableState());
            return BlockSnapshots.of(data.getMaterial(), data.getAsString());
        }
    }
}
