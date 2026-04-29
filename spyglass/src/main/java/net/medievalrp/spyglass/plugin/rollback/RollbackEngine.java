package net.medievalrp.spyglass.plugin.rollback;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.medievalrp.spyglass.plugin.command.service.ServiceSupport;
import net.kyori.adventure.text.Component;
import net.medievalrp.spyglass.api.event.BlockPlaceRecord;
import net.medievalrp.spyglass.api.event.BlockSnapshot;
import net.medievalrp.spyglass.api.event.BlockUseRecord;
import net.medievalrp.spyglass.api.event.Origin;
import net.medievalrp.spyglass.api.event.RecordContext;
import net.medievalrp.spyglass.api.event.Source;
import net.medievalrp.spyglass.api.event.StoredItem;
import net.medievalrp.spyglass.api.rollback.RollbackEffect;
import net.medievalrp.spyglass.api.rollback.RollbackEffectHandler;
import net.medievalrp.spyglass.api.rollback.RollbackReason;
import net.medievalrp.spyglass.api.rollback.RollbackResult;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.plugin.listener.RecordingSupport;
import net.medievalrp.spyglass.plugin.pipeline.Recorder;
import net.medievalrp.spyglass.plugin.util.BlockLocations;
import net.medievalrp.spyglass.plugin.util.BlockSnapshots;
import net.medievalrp.spyglass.plugin.util.ItemSerialization;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Banner;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.Jukebox;
import org.bukkit.block.Sign;
import org.bukkit.block.banner.Pattern;
import org.bukkit.block.banner.PatternType;
import org.bukkit.block.sign.Side;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class RollbackEngine {

    private final Recorder recorder;
    private final RecordingSupport support;
    private java.util.function.Function<String, java.util.Optional<RollbackEffectHandler>> handlerLookup =
            type -> java.util.Optional.empty();

    /** Test-friendly default: no rollback-source records emitted. */
    public RollbackEngine() {
        this(null, null);
    }

    /**
     * Production constructor. When {@code recorder} and {@code support}
     * are non-null, every successfully-applied effect ALSO emits a
     * {@link BlockPlaceRecord} stamped with {@link Origin#rollback} and a
     * {@code "ROLLBACK"} environment source — so the wand on a rolled-
     * back block reads "ROLLBACK placed STONE" rather than nothing.
     */
    public RollbackEngine(Recorder recorder, RecordingSupport support) {
        this.recorder = recorder;
        this.support = support;
    }

    /**
     * Wire the lookup the engine uses to dispatch
     * {@link RollbackEffect.Custom} payloads to third-party handlers.
     * Set during plugin {@code onEnable}; tests leave the default
     * lookup (no handlers) in place.
     */
    public void setCustomEffectLookup(java.util.function.Function<String, java.util.Optional<RollbackEffectHandler>> lookup) {
        this.handlerLookup = lookup == null
                ? type -> java.util.Optional.empty()
                : lookup;
    }

    /**
     * Synchronous entry point — used by tests and by external callers that
     * already control their own scheduling. Wraps {@link #applyAllChunked}
     * with a {@link ServiceSupport#synchronous() synchronous} scheduler and
     * an unbounded batch so everything happens in a single call.
     *
     * <p>For production use, {@code RollbackService} calls
     * {@link #applyAllChunked} directly so the per-block apply phase can
     * yield between batches and stay under Paper's tick watchdog.
     */
    public List<RollbackResult> applyAll(List<RollbackEffect> effects, CommandSender sender) {
        return applyAllChunked(effects, sender, ServiceSupport.synchronous(), Integer.MAX_VALUE).join();
    }

    /**
     * Apply a rollback split across ticks.
     *
     * <p>Earlier revisions did the entire rollback in one main-thread call.
     * On the FAWE-less slow path each {@code BlockState.update} costs a few
     * ms; for a 2 000-block rollback that's tens of seconds in one tick,
     * which trips Paper's 60 s watchdog and crashes the server. Worse, the
     * synchronous flood of {@code rolled-place}/{@code rolled-break}
     * recorder events to ClickHouse OOM-killed CH at the same time, so the
     * data layer would tear down with the world half-rolled (the visible
     * "diagonal lines / 4×4 chunks" pattern operators reported).
     *
     * <p>This method now:
     * <ol>
     *   <li>Runs Phase 1 (precondition check) and Phase 2 (FAWE batch
     *       submit) inline — both are fast: Phase 1 is just block reads,
     *       Phase 2 hands the whole list to FAWE which schedules its own
     *       chunk-batched writes off-tick.</li>
     *   <li>Splits Phase 3 (per-block {@code BlockState.update}) into
     *       batches of {@code batchSize} effects per tick, yielding back
     *       to the main thread between batches via
     *       {@link ServiceSupport#onMainThreadLater}. Each batch also
     *       emits its own {@code rolled-*} recorder events, which keeps
     *       the ClickHouse insert rate proportional to the rollback
     *       throughput instead of dumping everything at once.</li>
     *   <li>Runs Phase 4 (container-slot batches) in one tick — small N
     *       (one batch per chest, not per slot) and already cheap.</li>
     *   <li>Splits Phase 5 (entity ops + custom handlers) the same way
     *       Phase 3 does.</li>
     * </ol>
     *
     * Returns a future that completes once every batch has run. The
     * caller (RollbackService) chains the summary line and undo-stack push
     * onto completion so the player gets feedback once everything's
     * actually applied — not when the work is just queued.
     *
     * <p>Must be invoked on the main thread (Phase 1 reads block state).
     * Subsequent batches are re-entered on the main thread by the
     * scheduler.
     *
     * @param effects   ordered list of rollback effects (already sorted
     *                  newest-first for ROLLBACK / oldest-first for RESTORE
     *                  by the caller)
     * @param sender    the operator running the command; receives action
     *                  bar progress messages once batching kicks in
     * @param scheduler bridges {@code Bukkit.getScheduler().runTaskLater}
     *                  for production and {@code Runnable.run()} for tests
     * @param batchSize number of effects to apply per tick before yielding;
     *                  {@link Integer#MAX_VALUE} = "do everything now"
     */
    public CompletableFuture<List<RollbackResult>> applyAllChunked(
            List<RollbackEffect> effects, CommandSender sender,
            ServiceSupport scheduler, int batchSize) {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("RollbackEngine.applyAllChunked must run on the main thread.");
        }
        CompletableFuture<List<RollbackResult>> done = new CompletableFuture<>();
        if (effects.isEmpty()) {
            done.complete(List.of());
            return done;
        }

        RollbackResult[] resultArray = new RollbackResult[effects.size()];
        boolean useFawe = FaweRollback.isAvailable();

        // Phase 1 — cheap precondition check for every BlockReplace.
        // Just material + blockdata, no full BlockSnapshot capture.
        List<Integer> faweCandidateIndices = new ArrayList<>();
        List<RollbackEffect.BlockReplace> faweCandidateEffects = new ArrayList<>();
        for (int index = 0; index < effects.size(); index++) {
            RollbackEffect effect = effects.get(index);
            if (!(effect instanceof RollbackEffect.BlockReplace br)) {
                continue;
            }
            Optional<World> world = BlockLocations.resolveWorld(br.location());
            if (world.isEmpty()) {
                resultArray[index] = new RollbackResult.Skipped(br,
                        new RollbackReason.InvalidLocation(br.location()));
                continue;
            }
            Block block = world.get().getBlockAt(br.location().x(), br.location().y(), br.location().z());
            BlockSnapshot expected = br.expectedCurrent();
            if (block.getType() != expected.material()
                    || !block.getBlockData().getAsString().equals(expected.blockData())) {
                BlockSnapshot lightweightActual = BlockSnapshots.of(
                        block.getType(), block.getBlockData().getAsString());
                resultArray[index] = new RollbackResult.Skipped(br,
                        new RollbackReason.BlockChanged(br.location(), expected, lightweightActual));
                continue;
            }
            faweCandidateIndices.add(index);
            faweCandidateEffects.add(br);
        }

        // Phase 2 — FAWE-batch submit. FAWE itself handles chunk-batching
        // off-tick; we just hand it the candidate list.
        boolean[] faweApplied = useFawe
                ? FaweRollback.applyAll(faweCandidateEffects)
                : new boolean[faweCandidateEffects.size()];

        // Cluster the parallel arrays by chunk so Phase 3 walks effects
        // in chunk order. Two wins:
        //   1. Bukkit's getBlockAt() hits a cached chunk for consecutive
        //      cells in the same chunk (fewer chunk-lookup hops).
        //   2. We refresh each chunk's client-side packet ONCE when we
        //      finish that chunk (see {@link ChunkRefreshState}), not
        //      every batch. For a 1M-block rollback that's the
        //      difference between a few hundred refresh packets and
        //      thousands.
        sortParallelByChunk(faweCandidateIndices, faweCandidateEffects, faweApplied);

        // Phase 3 — apply path. When FAWE/WE are present, Phase 2 has
        // already done the actual block writes off-tick, so Phase 3 for
        // simple blocks (the bulk of any rollback) is pure bookkeeping:
        // build inverse effects, mark resultArray, emit audit records,
        // track touched chunks. None of that needs the main thread. We
        // run it async and then bounce to main thread once at the end
        // to do tile-entity state updates + chunk-refresh packets.
        //
        // Without FAWE/WE we still need to write blocks ourselves, which
        // requires the main thread, so we fall back to the per-tick
        // batched loop.
        ChunkRefreshState refreshState = new ChunkRefreshState();
        if (useFawe) {
            applyOffThreadPhase3(effects, resultArray, faweCandidateIndices,
                    faweCandidateEffects, faweApplied, sender, scheduler, batchSize, done);
        } else {
            applyBlockReplaceBatch(0, effects, resultArray, faweCandidateIndices, faweCandidateEffects,
                    faweApplied, useFawe, sender, scheduler, batchSize, done, refreshState);
        }
        return done;
    }

    /**
     * Cluster the parallel arrays so consecutive entries share a chunk,
     * and within a chunk apply from low Y to high Y. The Y ordering is
     * the gravel/sand fix: when we restore a column where the bottom
     * supporting block was destroyed and gravel above it fell, we need
     * the support to be in place before we set the gravel, otherwise
     * Bukkit's gravity check converts the gravel back to a falling-
     * block entity on the next world tick. Bottom-up restore sidesteps
     * the issue. Stable on the (worldId, chunkX, chunkZ, y) key — falls
     * back to input order for true ties (same cell broken twice in the
     * window, the rare case).
     */
    private static void sortParallelByChunk(List<Integer> indices,
                                            List<RollbackEffect.BlockReplace> effects,
                                            boolean[] applied) {
        int n = indices.size();
        if (n <= 1) {
            return;
        }
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> {
            BlockLocation la = effects.get(a).location();
            BlockLocation lb = effects.get(b).location();
            int c = la.worldId().compareTo(lb.worldId());
            if (c != 0) return c;
            c = Integer.compare(la.x() >> 4, lb.x() >> 4);
            if (c != 0) return c;
            c = Integer.compare(la.z() >> 4, lb.z() >> 4);
            if (c != 0) return c;
            c = Integer.compare(la.y(), lb.y());  // bottom-up within chunk
            if (c != 0) return c;
            return Integer.compare(a, b); // stable: preserve input order on full ties
        });
        // Rewrite arrays under the new permutation.
        Integer[] newIndices = new Integer[n];
        @SuppressWarnings("unchecked")
        RollbackEffect.BlockReplace[] newEffects = new RollbackEffect.BlockReplace[n];
        boolean[] newApplied = new boolean[n];
        for (int i = 0; i < n; i++) {
            newIndices[i] = indices.get(order[i]);
            newEffects[i] = effects.get(order[i]);
            newApplied[i] = applied[order[i]];
        }
        for (int i = 0; i < n; i++) {
            indices.set(i, newIndices[i]);
            effects.set(i, newEffects[i]);
            applied[i] = newApplied[i];
        }
    }

    /** Tracks the chunk currently being written so each chunk's
     *  client-refresh packet is sent exactly once — when we move to a
     *  different chunk, or when the whole apply phase finishes. */
    private static final class ChunkRefreshState {
        UUID currentWorld;
        int currentChunkX = Integer.MIN_VALUE;
        int currentChunkZ = Integer.MIN_VALUE;
        boolean pending = false;
    }

    /**
     * One tick's worth of Phase 3 work: apply up to {@code batchSize}
     * BlockReplace effects from {@code faweCandidate*} starting at index
     * {@code from}, then either schedule the next batch on the next tick
     * or hand off to Phase 4+5.
     */
    private void applyBlockReplaceBatch(int from,
                                        List<RollbackEffect> effects,
                                        RollbackResult[] resultArray,
                                        List<Integer> faweCandidateIndices,
                                        List<RollbackEffect.BlockReplace> faweCandidateEffects,
                                        boolean[] faweApplied,
                                        boolean useFawe,
                                        CommandSender sender,
                                        ServiceSupport scheduler,
                                        int batchSize,
                                        CompletableFuture<List<RollbackResult>> done,
                                        ChunkRefreshState refreshState) {
        int total = faweCandidateIndices.size();
        int to = Math.min(from + batchSize, total);
        // Effects are sorted by chunk (Phase 1.5) so consecutive entries
        // share a chunk almost always. We send one chunk-refresh packet
        // per chunk at the moment we leave it (i.e. the next effect is
        // in a different chunk). At the end of all batches a final
        // flush refreshes the last chunk.
        for (int i = from; i < to; i++) {
            int targetIndex = faweCandidateIndices.get(i);
            RollbackEffect.BlockReplace effect = faweCandidateEffects.get(i);
            BlockSnapshot replacement = effect.replacement();
            BlockLocation loc = effect.location();
            int chunkX = loc.x() >> 4;
            int chunkZ = loc.z() >> 4;
            // Detect chunk boundary BEFORE applying — we want to flush
            // the previous chunk's refresh packet only after its last
            // effect has landed, which is the iteration BEFORE this one
            // (state was left at "pending = true, currentChunk = prev"
            // by the previous iteration's effect). Flushing here means
            // the previous chunk's packet goes out as we transition.
            boolean chunkChanged = !refreshState.pending
                    || refreshState.currentWorld == null
                    || !refreshState.currentWorld.equals(loc.worldId())
                    || refreshState.currentChunkX != chunkX
                    || refreshState.currentChunkZ != chunkZ;
            if (chunkChanged && refreshState.pending) {
                refreshChunk(refreshState.currentWorld,
                        refreshState.currentChunkX, refreshState.currentChunkZ);
                refreshState.pending = false;
            }
            try {
                if (useFawe && faweApplied[i]) {
                    if (!FaweRollback.isSimple(replacement)) {
                        Block block = BlockLocations.resolveWorld(loc).orElseThrow()
                                .getBlockAt(loc.x(), loc.y(), loc.z());
                        applyTileEntityState(block, replacement);
                    }
                } else {
                    Block block = BlockLocations.resolveWorld(loc).orElseThrow()
                            .getBlockAt(loc.x(), loc.y(), loc.z());
                    applySnapshot(block, replacement);
                }
                refreshState.currentWorld = loc.worldId();
                refreshState.currentChunkX = chunkX;
                refreshState.currentChunkZ = chunkZ;
                refreshState.pending = true;
                RollbackEffect inverse = new RollbackEffect.BlockReplace(
                        loc, replacement, effect.expectedCurrent());
                resultArray[targetIndex] = new RollbackResult.Applied(effect, inverse);
                emitRollbackSourceRecord(sender, loc, replacement);
            } catch (RuntimeException thrown) {
                resultArray[targetIndex] = new RollbackResult.Skipped(effect,
                        new RollbackReason.Error("Unhandled error: " + thrown.getMessage()));
            }
        }
        if (sender instanceof Player p && total > batchSize && to < total) {
            p.sendActionBar(Component.text("Rolling back " + to + " / " + total));
        }
        if (to < total) {
            scheduler.onMainThreadLater(1L, () -> applyBlockReplaceBatch(
                    to, effects, resultArray, faweCandidateIndices, faweCandidateEffects,
                    faweApplied, useFawe, sender, scheduler, batchSize, done, refreshState));
        } else {
            // Final flush: refresh the last chunk we wrote to before
            // moving on. Phase 4/5 don't refresh chunks themselves.
            if (refreshState.pending) {
                refreshChunk(refreshState.currentWorld,
                        refreshState.currentChunkX, refreshState.currentChunkZ);
                refreshState.pending = false;
            }
            runContainerAndLeftover(effects, resultArray, sender, scheduler, batchSize, done);
        }
    }

    /**
     * Off-thread variant of Phase 3 used when FAWE / vanilla WE handled
     * the block writes in Phase 2. Iterates every effect on an async
     * thread doing only off-thread-safe work — building inverse effects,
     * marking {@code resultArray}, emitting {@code rolled-place} audit
     * records, accumulating tile-entity touch-ups and the set of chunks
     * to refresh. Then dispatches a single main-thread task at the end
     * to drain those two queues and continue into Phase 4/5.
     *
     * <p>The main-thread cost of a 1M-block stone-fill rollback drops
     * from ~5 seconds (with {@code batchSize=4000} → 250 ticks at
     * ~20ms each) to ~50ms (one tick of tile-entity + chunk-refresh
     * work at the end), with the heavy lifting moved to a JVM thread
     * Bukkit doesn't care about. For tile-entity-heavy rollbacks the
     * main-thread cost still scales with the tile-entity count; the
     * win shows up where it always does — large simple-block ops.
     */
    private void applyOffThreadPhase3(List<RollbackEffect> effects,
                                      RollbackResult[] resultArray,
                                      List<Integer> faweCandidateIndices,
                                      List<RollbackEffect.BlockReplace> faweCandidateEffects,
                                      boolean[] faweApplied,
                                      CommandSender sender,
                                      ServiceSupport scheduler,
                                      int batchSize,
                                      CompletableFuture<List<RollbackResult>> done) {
        scheduler.onAsyncThread(() -> {
            int total = faweCandidateIndices.size();
            // Tile-entity effects (containers, signs, banners, jukeboxes)
            // need {@code BlockState.update} on the main thread. Same for
            // the rare case where FAWE/WE failed for a specific block —
            // we'd fall back to {@link #applySnapshot}.
            List<TileEntityWork> tileEntityWork = new ArrayList<>();
            // Chunks we need to send a fresh chunk-data packet for at end.
            // Map<worldId, set-of-packed-chunk-coords>; one packet per
            // chunk regardless of how many cells we touched in it.
            Map<UUID, Set<Long>> chunksToRefresh = new HashMap<>();

            for (int i = 0; i < total; i++) {
                int targetIndex = faweCandidateIndices.get(i);
                RollbackEffect.BlockReplace effect = faweCandidateEffects.get(i);
                BlockSnapshot replacement = effect.replacement();
                BlockLocation loc = effect.location();
                try {
                    if (faweApplied[i]) {
                        if (!FaweRollback.isSimple(replacement)) {
                            // Tile-entity touch-up needed; defer to main thread.
                            tileEntityWork.add(new TileEntityWork(loc, replacement, false));
                        }
                        // simple block: Phase 2 finished it. Off-thread bookkeeping only.
                    } else {
                        // FAWE/WE failed for this cell; main-thread fallback writes it.
                        tileEntityWork.add(new TileEntityWork(loc, replacement, true));
                    }
                    long packed = ((long) (loc.x() >> 4) << 32) | ((loc.z() >> 4) & 0xFFFFFFFFL);
                    chunksToRefresh.computeIfAbsent(loc.worldId(), k -> new HashSet<>()).add(packed);
                    RollbackEffect inverse = new RollbackEffect.BlockReplace(
                            loc, replacement, effect.expectedCurrent());
                    resultArray[targetIndex] = new RollbackResult.Applied(effect, inverse);
                    emitRollbackSourceRecord(sender, loc, replacement);
                } catch (RuntimeException thrown) {
                    resultArray[targetIndex] = new RollbackResult.Skipped(effect,
                            new RollbackReason.Error("Unhandled error: " + thrown.getMessage()));
                }
            }

            // Bounce to main thread for the tasks Bukkit insists be on
            // its tick. Tile-entity and chunk-refresh work scales with
            // touched-chunk count and tile-entity count (NOT total
            // effect count), so even for million-block rollbacks the
            // main-thread tail is small.
            scheduler.onMainThread(() -> finishOffThreadPhase3(
                    effects, resultArray, sender, scheduler, batchSize, done,
                    tileEntityWork, chunksToRefresh));
        });
    }

    private void finishOffThreadPhase3(List<RollbackEffect> effects,
                                        RollbackResult[] resultArray,
                                        CommandSender sender,
                                        ServiceSupport scheduler,
                                        int batchSize,
                                        CompletableFuture<List<RollbackResult>> done,
                                        List<TileEntityWork> tileEntityWork,
                                        Map<UUID, Set<Long>> chunksToRefresh) {
        for (TileEntityWork w : tileEntityWork) {
            try {
                Block block = BlockLocations.resolveWorld(w.location).orElseThrow()
                        .getBlockAt(w.location.x(), w.location.y(), w.location.z());
                if (w.fullSnapshot) {
                    applySnapshot(block, w.snapshot);
                } else {
                    applyTileEntityState(block, w.snapshot);
                }
            } catch (RuntimeException ignored) {
                // The matching resultArray slot is already Applied (we
                // recorded the inverse off-thread); a tile-entity touch-
                // up failure leaves the simple block correct, just with
                // empty container contents. Acceptable.
            }
        }
        for (Map.Entry<UUID, Set<Long>> entry : chunksToRefresh.entrySet()) {
            World world = Bukkit.getWorld(entry.getKey());
            if (world == null) continue;
            for (long packed : entry.getValue()) {
                int cx = (int) (packed >> 32);
                int cz = (int) packed;
                try {
                    world.refreshChunk(cx, cz);
                } catch (RuntimeException ignored) {
                    // see refreshChunk() comment — best-effort
                }
            }
        }
        runContainerAndLeftover(effects, resultArray, sender, scheduler, batchSize, done);
    }

    /** Deferred main-thread work captured by the off-thread Phase 3
     *  pass. {@code fullSnapshot=true} means FAWE/WE failed for that
     *  cell and we need to do the whole snapshot-write on main thread;
     *  {@code false} means just the tile-entity portion. */
    private record TileEntityWork(BlockLocation location, BlockSnapshot snapshot, boolean fullSnapshot) {
    }

    /** Send a single chunk-data packet for one chunk. Catches errors
     *  from non-Bukkit test envs / unloaded worlds and continues — the
     *  fallback (per-block change packet from Bukkit) still gets the
     *  cells right, refresh just makes it instant. */
    private void refreshChunk(UUID worldId, int cx, int cz) {
        if (worldId == null) {
            return;
        }
        try {
            World world = Bukkit.getWorld(worldId);
            if (world != null) {
                world.refreshChunk(cx, cz);
            }
        } catch (RuntimeException ignored) {
            // see refreshTouchedChunks comment — best-effort
        }
    }


    /**
     * Phase 4 (containers, single-tick) → Phase 5 (entity ops + custom
     * handlers, chunked the same way Phase 3 is). Called once Phase 3 has
     * fully drained.
     */
    private void runContainerAndLeftover(List<RollbackEffect> effects,
                                         RollbackResult[] resultArray,
                                         CommandSender sender,
                                         ServiceSupport scheduler,
                                         int batchSize,
                                         CompletableFuture<List<RollbackResult>> done) {
        // Phase 4 — group ContainerSlotWrites by location and apply each
        // chest in a single getState/update cycle. Typically <100
        // locations even on big rollbacks; one tick is enough.
        Map<BlockLocation, List<Integer>> slotIndicesByLocation = new LinkedHashMap<>();
        Map<BlockLocation, List<RollbackEffect.ContainerSlotWrite>> slotEffectsByLocation = new LinkedHashMap<>();
        for (int index = 0; index < effects.size(); index++) {
            if (resultArray[index] != null) {
                continue;
            }
            if (effects.get(index) instanceof RollbackEffect.ContainerSlotWrite csw) {
                slotIndicesByLocation.computeIfAbsent(csw.location(), k -> new ArrayList<>()).add(index);
                slotEffectsByLocation.computeIfAbsent(csw.location(), k -> new ArrayList<>()).add(csw);
            }
        }
        for (BlockLocation location : slotIndicesByLocation.keySet()) {
            applyContainerBatch(location,
                    slotIndicesByLocation.get(location),
                    slotEffectsByLocation.get(location),
                    resultArray);
        }

        // Phase 5 — leftover effects (entity spawn/remove, custom).
        applyLeftoverBatch(0, effects, resultArray, sender, scheduler, batchSize, done);
    }

    /** Phase 5 batched the same way Phase 3 is. */
    private void applyLeftoverBatch(int from,
                                    List<RollbackEffect> effects,
                                    RollbackResult[] resultArray,
                                    CommandSender sender,
                                    ServiceSupport scheduler,
                                    int batchSize,
                                    CompletableFuture<List<RollbackResult>> done) {
        int total = effects.size();
        int processed = 0;
        int i = from;
        while (i < total && processed < batchSize) {
            if (resultArray[i] == null) {
                RollbackEffect effect = effects.get(i);
                try {
                    resultArray[i] = apply(effect);
                } catch (RuntimeException thrown) {
                    resultArray[i] = new RollbackResult.Skipped(effect,
                            new RollbackReason.Error("Unhandled error: " + thrown.getMessage()));
                }
                processed++;
            }
            i++;
        }
        if (i < total) {
            int next = i;
            scheduler.onMainThreadLater(1L, () -> applyLeftoverBatch(
                    next, effects, resultArray, sender, scheduler, batchSize, done));
        } else {
            done.complete(List.of(resultArray));
        }
    }

    /**
     * Apply ONLY the tile-entity portion of {@code snapshot} to
     * {@code block} — assumes the material + blockdata are already
     * correct (e.g. just written by FAWE). Calls {@code BlockState.update}
     * with physics off; skips the redundant {@code block.setType} and
     * {@code state.setBlockData} that {@link #applySnapshot} performs.
     */
    private void applyTileEntityState(Block block, BlockSnapshot snapshot) {
        BlockState state = block.getState();

        if (state instanceof Container container) {
            Inventory inventory = container.getSnapshotInventory();
            inventory.clear();
            for (StoredItem item : snapshot.containerItems()) {
                inventory.setItem(item.slot(), ItemSerialization.decode(item.data()));
            }
        }

        if (state instanceof Sign sign) {
            writeSign(sign, Side.FRONT, snapshot.signFront());
            writeSign(sign, Side.BACK, snapshot.signBack());
        }

        if (state instanceof Banner banner) {
            List<Pattern> patterns = snapshot.bannerPatterns().stream()
                    .map(this::parsePattern)
                    .flatMap(Optional::stream)
                    .toList();
            banner.setPatterns(patterns);
        }

        if (state instanceof Jukebox jukebox) {
            jukebox.getSnapshotInventory().setItem(0, ItemSerialization.decode(snapshot.jukeboxRecord()));
        }

        state.update(true, false);
    }

    /**
     * Apply every {@code ContainerSlotWrite} for one location in a single
     * {@code getState}/{@code update} cycle. Each slot still gets its
     * own precondition check + per-slot skip reason; only the surrounding
     * Bukkit roundtrips collapse.
     */
    private void applyContainerBatch(BlockLocation location,
                                     List<Integer> indices,
                                     List<RollbackEffect.ContainerSlotWrite> writes,
                                     RollbackResult[] resultArray) {
        Optional<World> world = BlockLocations.resolveWorld(location);
        if (world.isEmpty()) {
            for (int i = 0; i < indices.size(); i++) {
                resultArray[indices.get(i)] = new RollbackResult.Skipped(writes.get(i),
                        new RollbackReason.InvalidLocation(location));
            }
            return;
        }
        Block block = world.get().getBlockAt(location.x(), location.y(), location.z());
        BlockState state = block.getState();
        if (!(state instanceof Container container)) {
            for (int i = 0; i < indices.size(); i++) {
                resultArray[indices.get(i)] = new RollbackResult.Skipped(writes.get(i),
                        new RollbackReason.NotSupported("Target is no longer a container."));
            }
            return;
        }

        Inventory inventory = container.getSnapshotInventory();
        int size = inventory.getSize();
        boolean anyApplied = false;

        for (int i = 0; i < indices.size(); i++) {
            RollbackEffect.ContainerSlotWrite csw = writes.get(i);
            int slot = csw.slot();
            if (slot < 0 || slot >= size) {
                resultArray[indices.get(i)] = new RollbackResult.Skipped(csw,
                        new RollbackReason.NotSupported(
                                "Slot " + slot + " out of range for container (size " + size + ")."));
                continue;
            }
            ItemStack current = inventory.getItem(slot);
            if (!matches(csw.expectedCurrent(), slot, current)) {
                resultArray[indices.get(i)] = new RollbackResult.Skipped(csw,
                        new RollbackReason.Error("Container slot changed."));
                continue;
            }
            StoredItem replacement = csw.replacement();
            inventory.setItem(slot, replacement == null ? null : ItemSerialization.decode(replacement.data()));
            StoredItem inverseCurrent = ItemSerialization.storedItem(slot, current);
            RollbackEffect inverse = new RollbackEffect.ContainerSlotWrite(
                    location, slot, replacement, inverseCurrent);
            resultArray[indices.get(i)] = new RollbackResult.Applied(csw, inverse);
            anyApplied = true;
        }

        if (anyApplied) {
            state.update(true, false);
        }
    }

    /**
     * Emit a lightweight rollback-source record so the wand and search
     * show "ROLLBACK placed STONE" / "ROLLBACK broke STONE" at the
     * affected block. Uses {@link BlockUseRecord} (just context + target
     * material) rather than a {@link BlockPlaceRecord} that would carry
     * heavy {@link BlockSnapshot} before/after blobs — for a 150-block
     * rollback that's the difference between 150 lightweight records and
     * 150 records each holding two snapshots, which (combined with FAWE's
     * write buffer) was enough to push the JVM heap into FAWE's "slow
     * down to avoid OOM" mode and watchdog the tick.
     *
     * <p>Event name flips on the replacement: AIR target reads as
     * {@code "rolledBreak"} (undo of a place / restore-to-air), non-AIR
     * reads as {@code "rolledPlace"} (rollback of a break / restore from
     * air). Past-tense rendering is configured in {@code config.conf}
     * under {@code events.rolledBreak} / {@code events.rolledPlace}.
     */
    private void emitRollbackSourceRecord(CommandSender sender, BlockLocation location, BlockSnapshot after) {
        if (recorder == null || support == null) {
            return;
        }
        String operatorName = sender == null ? "console" : sender.getName();
        Instant occurred = support.now();
        Source source = Source.environment("ROLLBACK");
        Origin origin = Origin.rollback(operatorName);
        // Build RecordContext directly with a fast-random UUID rather
        // than going through {@code support.context} which delegates to
        // {@link RecordContext#fresh} (uses SecureRandom). 150 rollback
        // blocks = 150 entropy reads on the main thread otherwise.
        RecordContext ctx = new RecordContext(
                RecordingSupport.fastRandomUUID(),
                occurred,
                support.expiresAt(occurred),
                origin, source, location, support.serverName());
        boolean toAir = after == null || after.material() == Material.AIR;
        // Lowercase + hyphenated names match {@code shulker-open} et al
        // and dodge the case-sensitivity quirk in {@link
        // net.medievalrp.spyglass.api.event.EventCatalog#recordClassOf}
        // (it lowercases on lookup but the map preserves case).
        String event = toAir ? "rolled-break" : "rolled-place";
        String target = (after == null ? Material.AIR : after.material()).name();
        recorder.record(new BlockUseRecord(
                ctx.id(), event, ctx.occurred(), ctx.expiresAt(),
                ctx.origin(), ctx.source(), ctx.location(), ctx.server(), target));
    }

    public RollbackResult apply(RollbackEffect effect) {
        return switch (effect) {
            case RollbackEffect.BlockReplace replace -> applyBlockReplace(replace);
            case RollbackEffect.ContainerSlotWrite slotWrite -> applyContainerSlotWrite(slotWrite);
            case RollbackEffect.EntitySpawn spawn -> applyEntitySpawn(spawn);
            case RollbackEffect.EntityRemove remove -> applyEntityRemove(remove);
            case RollbackEffect.Custom custom -> applyCustom(custom);
        };
    }

    private RollbackResult applyCustom(RollbackEffect.Custom effect) {
        java.util.Optional<RollbackEffectHandler> handler = handlerLookup.apply(effect.type());
        if (handler.isEmpty()) {
            return new RollbackResult.Skipped(effect, new RollbackReason.NotSupported(
                    "No handler registered for custom effect type '" + effect.type() + "'."));
        }
        try {
            RollbackResult result = handler.get().apply(effect);
            return result == null
                    ? new RollbackResult.Skipped(effect,
                            new RollbackReason.Error("Custom handler returned null."))
                    : result;
        } catch (RuntimeException thrown) {
            return new RollbackResult.Skipped(effect, new RollbackReason.Error(
                    "Custom handler '" + effect.type() + "' threw: " + thrown.getMessage()));
        }
    }

    private RollbackResult applyEntitySpawn(RollbackEffect.EntitySpawn effect) {
        if (effect.serializedEntity() == null || effect.serializedEntity().isBlank()) {
            return new RollbackResult.Skipped(effect, new RollbackReason.NotSupported(
                    "Entity NBT not captured (record is pre-Block-11)."));
        }
        Optional<World> world = BlockLocations.resolveWorld(effect.location());
        if (world.isEmpty()) {
            return new RollbackResult.Skipped(effect, new RollbackReason.InvalidLocation(effect.location()));
        }
        Location location = new Location(world.get(),
                effect.location().x() + 0.5, effect.location().y(), effect.location().z() + 0.5);
        try {
            byte[] bytes = Base64.getDecoder().decode(effect.serializedEntity());
            Entity entity = Bukkit.getUnsafe().deserializeEntity(bytes, world.get(), true, false);
            if (entity == null) {
                return new RollbackResult.Skipped(effect, new RollbackReason.NotSupported(
                        "Entity deserialization returned null."));
            }
            entity.teleport(location);
            RollbackEffect inverse = new RollbackEffect.EntityRemove(
                    effect.location(), effect.entityType(), entity.getUniqueId().toString());
            return new RollbackResult.Applied(effect, inverse);
        } catch (Throwable thrown) {
            return new RollbackResult.Skipped(effect, new RollbackReason.Error(
                    "Entity spawn failed: " + thrown.getMessage()));
        }
    }

    private RollbackResult applyEntityRemove(RollbackEffect.EntityRemove effect) {
        if (effect.entityId() == null || effect.entityId().isBlank()) {
            return new RollbackResult.Skipped(effect, new RollbackReason.NotSupported("No entity id."));
        }
        UUID entityId;
        try {
            entityId = UUID.fromString(effect.entityId());
        } catch (IllegalArgumentException ex) {
            return new RollbackResult.Skipped(effect, new RollbackReason.NotSupported("Invalid entity id."));
        }
        Entity entity = Bukkit.getEntity(entityId);
        if (entity == null) {
            return new RollbackResult.Skipped(effect, new RollbackReason.NotSupported("Entity not found."));
        }
        entity.remove();
        RollbackEffect inverse = new RollbackEffect.EntitySpawn(effect.location(), effect.entityType(), null);
        return new RollbackResult.Applied(effect, inverse);
    }

    private RollbackResult applyBlockReplace(RollbackEffect.BlockReplace effect) {
        Optional<World> world = BlockLocations.resolveWorld(effect.location());
        if (world.isEmpty()) {
            return new RollbackResult.Skipped(effect, new RollbackReason.InvalidLocation(effect.location()));
        }

        Block block = world.get().getBlockAt(effect.location().x(), effect.location().y(), effect.location().z());
        BlockSnapshot actual = BlockSnapshots.capture(block.getState());
        if (!matches(effect.expectedCurrent(), actual)) {
            return new RollbackResult.Skipped(effect, new RollbackReason.BlockChanged(effect.location(), effect.expectedCurrent(), actual));
        }

        applySnapshot(block, effect.replacement());
        RollbackEffect inverse = new RollbackEffect.BlockReplace(effect.location(), effect.replacement(), actual);
        return new RollbackResult.Applied(effect, inverse);
    }

    private RollbackResult applyContainerSlotWrite(RollbackEffect.ContainerSlotWrite effect) {
        Optional<World> world = BlockLocations.resolveWorld(effect.location());
        if (world.isEmpty()) {
            return new RollbackResult.Skipped(effect, new RollbackReason.InvalidLocation(effect.location()));
        }
        Block block = world.get().getBlockAt(effect.location().x(), effect.location().y(), effect.location().z());
        if (!(block.getState() instanceof Container container)) {
            return new RollbackResult.Skipped(effect, new RollbackReason.NotSupported("Target is no longer a container."));
        }

        // Some Bukkit inventory events report slot = -1 when the
        // change wasn't tied to a specific slot (auto-stacking, drag
        // drops, etc.) and the original container is sometimes wider
        // than the current one (player downgraded chest -> hopper).
        // Validate the slot against the live inventory size before
        // calling getItem(), which throws IndexOutOfBoundsException
        // for out-of-range indices.
        int slot = effect.slot();
        int size = container.getInventory().getSize();
        if (slot < 0 || slot >= size) {
            return new RollbackResult.Skipped(effect,
                    new RollbackReason.NotSupported(
                            "Slot " + slot + " out of range for container (size " + size + ")."));
        }

        ItemStack current = container.getInventory().getItem(slot);
        if (!matches(effect.expectedCurrent(), effect.slot(), current)) {
            return new RollbackResult.Skipped(effect, new RollbackReason.Error("Container slot changed."));
        }

        StoredItem replacement = effect.replacement();
        container.getInventory().setItem(effect.slot(), replacement == null ? null : ItemSerialization.decode(replacement.data()));
        StoredItem inverseCurrent = ItemSerialization.storedItem(effect.slot(), current);
        RollbackEffect inverse = new RollbackEffect.ContainerSlotWrite(effect.location(), effect.slot(), replacement, inverseCurrent);
        return new RollbackResult.Applied(effect, inverse);
    }

    private void applySnapshot(Block block, BlockSnapshot snapshot) {
        block.setType(snapshot.material(), false);
        BlockState state = block.getState();
        state.setBlockData(Bukkit.createBlockData(snapshot.blockData()));

        if (state instanceof Container container) {
            Inventory inventory = container.getSnapshotInventory();
            inventory.clear();
            for (StoredItem item : snapshot.containerItems()) {
                inventory.setItem(item.slot(), ItemSerialization.decode(item.data()));
            }
        }

        if (state instanceof Sign sign) {
            writeSign(sign, Side.FRONT, snapshot.signFront());
            writeSign(sign, Side.BACK, snapshot.signBack());
        }

        if (state instanceof Banner banner) {
            List<Pattern> patterns = snapshot.bannerPatterns().stream()
                    .map(this::parsePattern)
                    .flatMap(Optional::stream)
                    .toList();
            banner.setPatterns(patterns);
        }

        if (state instanceof Jukebox jukebox) {
            jukebox.getSnapshotInventory().setItem(0, ItemSerialization.decode(snapshot.jukeboxRecord()));
        }

        state.update(true, false);
    }

    private boolean matches(BlockSnapshot expected, BlockSnapshot actual) {
        return expected.material() == actual.material() && expected.blockData().equals(actual.blockData());
    }

    private boolean matches(StoredItem expected, int slot, ItemStack current) {
        if (expected == null) {
            return current == null || current.getType() == Material.AIR;
        }
        return expected.slot() == slot
                && current != null
                && expected.material().equals(current.getType().name())
                && expected.data().equals(ItemSerialization.encode(current));
    }

    private void writeSign(Sign sign, Side side, List<String> lines) {
        for (int index = 0; index < Math.min(4, lines.size()); index++) {
            sign.getSide(side).line(index, Component.text(lines.get(index)));
        }
    }

    private Optional<Pattern> parsePattern(String value) {
        String[] parts = value.split(":", 2);
        if (parts.length != 2) {
            return Optional.empty();
        }
        try {
            PatternType type = PatternType.getByIdentifier(parts[1]);
            if (type == null) {
                return Optional.empty();
            }
            return Optional.of(new Pattern(org.bukkit.DyeColor.valueOf(parts[0]), type));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }
}
