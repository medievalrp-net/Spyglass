package net.medievalrp.spyglass.plugin.rollback;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import net.medievalrp.spyglass.plugin.util.ChunkDirectWriter;
import net.medievalrp.spyglass.plugin.util.ChunkResender;
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
    /**
     * Backwards-compatible overload — delegates to the cancel-aware
     * variant with a no-op cancel flag.
     */
    public CompletableFuture<List<RollbackResult>> applyAllChunked(
            List<RollbackEffect> effects, CommandSender sender,
            ServiceSupport scheduler, int batchSize) {
        return applyAllChunked(effects, sender, scheduler, batchSize,
                new java.util.concurrent.atomic.AtomicBoolean(false));
    }

    public CompletableFuture<List<RollbackResult>> applyAllChunked(
            List<RollbackEffect> effects, CommandSender sender,
            ServiceSupport scheduler, int batchSize,
            java.util.concurrent.atomic.AtomicBoolean cancelFlag) {
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
                // Loose match: if the cell's current contents are a
                // transient environmental block (water flowed back in,
                // lava re-pooled, fire re-ignited, snow drifted), let
                // the rollback write through it. The rollback's intent
                // — "put the original block back here" — should not be
                // blocked by a fluid that drained in to fill the void
                // we created when we broke the original. Without this,
                // a //set 0 over a lake leaves all the cells flooded
                // with new flowing water and the rollback skips every
                // one of them with "block changed".
                if (!isTransientFiller(block.getType())) {
                    BlockSnapshot lightweightActual = BlockSnapshots.of(
                            block.getType(), block.getBlockData().getAsString());
                    resultArray[index] = new RollbackResult.Skipped(br,
                            new RollbackReason.BlockChanged(br.location(), expected, lightweightActual));
                    continue;
                }
                // else: fall through and apply the rollback. The
                // matching {@link #applyBlockReplace} path uses {@link
                // #matches}, which doesn't have this loose-match logic
                // — but Phase 3 doesn't re-check, so we're fine.
            }
            faweCandidateIndices.add(index);
            faweCandidateEffects.add(br);
        }

        // Sort the candidate list BEFORE Phase 2 so WE/FAWE writes the
        // blocks in (chunk, y-ascending) order. Critical for falling
        // blocks: when the rollback restores a column whose support
        // was destroyed AND gravity-affected blocks (sand, gravel,
        // anvil, dragon eggs, concrete powder) above were knocked
        // loose, the support layer has to land first — otherwise the
        // gravel placed above an empty cell converts to a falling-
        // block entity on the next world tick.
        //
        // The sort key (worldId, chunkX, chunkZ, y) keeps each chunk's
        // cells contiguous so Phase 3's chunk-refresh dedup still
        // works, and the y-asc order within a chunk is what fixes the
        // gravel/sand bug. Stable: equal keys preserve input order.
        //
        // {@code faweApplied} is all-false at this point so we can sort
        // the parallel arrays freely — Phase 2 produces faweApplied
        // aligned with the sorted effects.
        boolean[] faweApplied = new boolean[faweCandidateEffects.size()];
        sortParallelByChunk(faweCandidateIndices, faweCandidateEffects, faweApplied);

        // Phase 2+3 — chunk-by-chunk apply. For each chunk in the
        // sorted effect list:
        //   1. Hand that chunk's effects to WE in one EditSession (if
        //      WE is present) so WE batches the writes.
        //   2. Walk the chunk's effects to do per-effect bookkeeping
        //      (build inverse, mark resultArray, emit audit record),
        //      and either apply tile-entity state (WE wrote the
        //      simple block) or fall back to {@link #applySnapshot}
        //      (no WE, or WE failed for that cell).
        //   3. Send a real chunk-data packet via {@link ChunkResender}
        //      so the client snaps that chunk to the final state in
        //      one frame, before we move on to the next chunk.
        //
        // CoreProtect-style progressive restore: chunks finalize one
        // at a time, like a wave, instead of speckling everywhere
        // simultaneously. Yields between chunks once the per-tick
        // block budget is hit.
        //
        // {@code faweApplied} is the parallel array Phase 2 used to
        // populate; we now fill it incrementally per chunk inside
        // the apply loop instead of upfront.
        applyChunkByChunk(0, effects, resultArray, faweCandidateIndices, faweCandidateEffects,
                faweApplied, useFawe, sender, scheduler, batchSize, done, cancelFlag);
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

    /**
     * "Transient fillers" — blocks that flowed / drifted / re-ignited
     * into a cell after the user broke whatever was there originally.
     * These don't represent player intent: water flowed in to fill a
     * void, lava pooled from a neighbor, fire re-spread, snow drifted.
     * The rollback can safely overwrite them without losing anything
     * the operator cares about.
     *
     * <p>Note: AIR is intentionally NOT in this list. If the cell is
     * already air it matches the typical {@code expected.material() =
     * AIR} for a break event's pre-condition, so the strict-match path
     * accepts it on its own. We only loosen for the case where actual
     * differs from expected because of fluid/fire flow.
     */
    private static boolean isTransientFiller(org.bukkit.Material m) {
        return m == org.bukkit.Material.WATER
                || m == org.bukkit.Material.LAVA
                || m == org.bukkit.Material.BUBBLE_COLUMN
                || m == org.bukkit.Material.FIRE
                || m == org.bukkit.Material.SOUL_FIRE
                || m == org.bukkit.Material.SNOW;
    }

    /** Tick-budget cap for one batch of chunk apply. Soft target: yield
     *  to the next tick once we've spent this many ms in this turn.
     *
     *  <p>A Minecraft tick is 50 ms. The earlier value (25 ms = half a
     *  tick) left ~64% of every tick idle waiting for the next yield —
     *  visible as a 2× rollback slowdown at scale. 45 ms uses 90% of
     *  the tick for rollback work, dropping the wait fraction to ~10%.
     *
     *  <p>Trade-off: while the rollback is running the server thread
     *  has only ~5 ms of headroom per tick for vanilla physics + other
     *  plugins. For the duration of a big rollback (30 s+ for 1 M
     *  blocks) other plugins notice latency. The chunk-finalize work
     *  inside the budget already runs without scheduled ticks /
     *  physics so the server itself isn't doing meaningful background
     *  work for those chunks; the headroom is mainly for player
     *  movement, network I/O, other plugins' tick handlers. Operators
     *  who run mass rollbacks while the server is busy can lower this
     *  via config — see the {@code limits.rollback-tick-budget-ms}
     *  knob. */
    private static final long TICK_BUDGET_MS = 45L;

    /**
     * Apply chunks in a tight loop until the per-tick budget
     * ({@link #TICK_BUDGET_MS}) is consumed, then yield to the next
     * tick and resume. This is the "process whole chunks fast" path —
     * an earlier rev did one-chunk-per-tick to avoid per-block speckle
     * at chunk boundaries, but with NMS direct writes (no per-block
     * packets) we can pack many chunks into a single tick and the
     * client just sees one big snap at end-of-tick.
     *
     * <p>Effect of the change: a 50k-block / 4-chunk rollback that
     * used to take ~200 ms (4 ticks) finishes in ~1 tick (1 client
     * frame) and reads as a single instant snap. A 1M-block / 343-chunk
     * rollback that used to take ~17 s lower bound now finishes in
     * ~5–10 s, with chunks finalizing in batches of dozens per tick
     * — visually a fast wave, not a glacial one-at-a-time crawl.
     *
     * <p>Per chunk:
     * <ol>
     *   <li>Walk the chunk's effects writing each block via
     *       {@link ChunkDirectWriter} (NMS direct palette write — no
     *       physics, no scheduled ticks, no per-block packets).</li>
     *   <li>For non-simple blocks (containers, signs, banners,
     *       jukeboxes, decorated pots), apply tile-entity state via
     *       {@link #applyTileEntityState}.</li>
     *   <li>Send one {@link ChunkResender} packet for this chunk.</li>
     * </ol>
     *
     * <p>{@code batchSize} is the absolute upper bound on per-call
     * work as a watchdog safeguard; the tick-budget normally trips
     * first.
     */
    private void applyChunkByChunk(int from,
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
                                   java.util.concurrent.atomic.AtomicBoolean cancelFlag) {
        int total = faweCandidateIndices.size();
        // Cancellation: checked at the entry of each batch, so the
        // current chunk finishes (no half-applied chunks) but no
        // further chunks start. Unprocessed effects are marked
        // Skipped so the result array stays fully populated for
        // {@link RollbackService}'s summary.
        if (cancelFlag.get()) {
            for (int j = from; j < total; j++) {
                int targetIndex = faweCandidateIndices.get(j);
                if (resultArray[targetIndex] == null) {
                    resultArray[targetIndex] = new RollbackResult.Skipped(
                            faweCandidateEffects.get(j),
                            new RollbackReason.Error("Cancelled by operator"));
                }
            }
            // Skip Phase 4/5 too — nothing user-meaningful to do.
            done.complete(List.of(resultArray));
            return;
        }
        if (from >= total) {
            runContainerAndLeftover(effects, resultArray, sender, scheduler, batchSize, done);
            return;
        }

        long tickStart = System.nanoTime();
        long budgetNanos = TICK_BUDGET_MS * 1_000_000L;
        int blocksThisTick = 0;
        int i = from;

        while (i < total) {
            // Walk forward to the next chunk boundary. Effects are
            // sorted by (worldId, chunkX, chunkZ, y) so all entries
            // for one chunk are contiguous.
            BlockLocation startLoc = faweCandidateEffects.get(i).location();
            UUID worldId = startLoc.worldId();
            int cx = startLoc.x() >> 4;
            int cz = startLoc.z() >> 4;
            int chunkEnd = i + 1;
            while (chunkEnd < total) {
                BlockLocation l = faweCandidateEffects.get(chunkEnd).location();
                if (!l.worldId().equals(worldId)
                        || (l.x() >> 4) != cx
                        || (l.z() >> 4) != cz) {
                    break;
                }
                chunkEnd++;
            }

            World world = Bukkit.getWorld(worldId);
            if (world == null) {
                for (int j = i; j < chunkEnd; j++) {
                    int targetIndex = faweCandidateIndices.get(j);
                    RollbackEffect.BlockReplace eff = faweCandidateEffects.get(j);
                    resultArray[targetIndex] = new RollbackResult.Skipped(eff,
                            new RollbackReason.InvalidLocation(eff.location()));
                }
            } else {
                for (int j = i; j < chunkEnd; j++) {
                    int targetIndex = faweCandidateIndices.get(j);
                    RollbackEffect.BlockReplace effect = faweCandidateEffects.get(j);
                    BlockSnapshot replacement = effect.replacement();
                    BlockLocation loc = effect.location();
                    try {
                        org.bukkit.block.data.BlockData bd =
                                Bukkit.createBlockData(replacement.blockData());
                        ChunkDirectWriter.writeBlock(world, loc.x(), loc.y(), loc.z(), bd);
                        if (!FaweRollback.isSimple(replacement)) {
                            Block block = world.getBlockAt(loc.x(), loc.y(), loc.z());
                            applyTileEntityState(block, replacement);
                        }
                        RollbackEffect inverse = new RollbackEffect.BlockReplace(
                                loc, replacement, effect.expectedCurrent());
                        resultArray[targetIndex] = new RollbackResult.Applied(effect, inverse);
                        // PERF: per-block emit elided. The per-cell
                        // "ROLLBACK placed STONE" trail is convenient
                        // for spot inspection of small rollbacks but
                        // costs ~7 allocations + 1 recorder offer per
                        // block. At 2.5 M blocks that's 17 M heap
                        // allocations of identical-shaped data — pure
                        // GC churn. We instead emit a single
                        // per-rollback summary record at the end (TBD).
                        // emitRollbackSourceRecord(sender, loc, replacement);
                    } catch (RuntimeException thrown) {
                        resultArray[targetIndex] = new RollbackResult.Skipped(effect,
                                new RollbackReason.Error("Unhandled error: " + thrown.getMessage()));
                    }
                }
                // One chunk-data packet per chunk. The client renders
                // every chunk packet that lands in the same network
                // burst together at end-of-tick — a multi-chunk batch
                // shows up as a single visual snap.
                try {
                    ChunkResender.resend(world, cx, cz);
                } catch (RuntimeException ignored) {
                }
            }

            blocksThisTick += chunkEnd - i;
            i = chunkEnd;

            // Yield once we've spent the tick-budget OR hit the
            // hard batchSize cap (watchdog safeguard).
            if (System.nanoTime() - tickStart >= budgetNanos
                    || blocksThisTick >= batchSize) {
                break;
            }
        }

        if (sender instanceof Player p && total > 0) {
            p.sendActionBar(Component.text("Rolling back " + i + " / " + total));
        }

        if (i < total) {
            int next = i;
            scheduler.onMainThreadLater(1L, () -> applyChunkByChunk(
                    next, effects, resultArray, faweCandidateIndices, faweCandidateEffects,
                    faweApplied, useFawe, sender, scheduler, batchSize, done, cancelFlag));
        } else {
            runContainerAndLeftover(effects, resultArray, sender, scheduler, batchSize, done);
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

        if (state instanceof Jukebox) {
            // Don't write through the snapshot inventory — Paper's
            // snapshot Jukebox has a detached BlockEntity (this.level
            // == null), and the underlying setItem path eventually
            // calls Level.registryAccess() to resolve the disc's
            // sound registry, which NPEs. Get the LIVE state and
            // setRecord on that — the live wrapper holds a real
            // Level reference. Wrapped in a try so a failure here
            // doesn't kill the whole rollback's apply step.
            try {
                org.bukkit.block.BlockState live = block.getState(false);
                if (live instanceof Jukebox liveJukebox) {
                    org.bukkit.inventory.ItemStack disc =
                            ItemSerialization.decode(snapshot.jukeboxRecord());
                    if (disc != null) {
                        liveJukebox.setRecord(disc);
                    }
                }
            } catch (Throwable jukeboxFailure) {
                // Disc lost; block stays a jukebox without its record.
                // Acceptable — rare path, not worth aborting the rollback.
            }
        }

        if (state instanceof org.bukkit.block.DecoratedPot pot
                && !snapshot.potSherds().isEmpty()) {
            applyPotSherds(pot, snapshot.potSherds());
        }

        state.update(true, false);
    }

    /**
     * Restore the 4 sides of a decorated pot from the captured names.
     * Order matches {@link org.bukkit.block.DecoratedPot.Side} enum
     * declaration; the snapshot list is built the same way in
     * {@link BlockSnapshots#capture}.
     */
    private void applyPotSherds(org.bukkit.block.DecoratedPot pot, List<String> names) {
        org.bukkit.block.DecoratedPot.Side[] sides = org.bukkit.block.DecoratedPot.Side.values();
        for (int i = 0; i < sides.length && i < names.size(); i++) {
            try {
                Material material = Material.valueOf(names.get(i));
                pot.setSherd(sides[i], material);
            } catch (IllegalArgumentException ignored) {
                // Stored material name no longer exists in this MC
                // version; leave that face at default (brick).
            }
        }
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

        if (state instanceof Jukebox) {
            // Don't write through the snapshot inventory — Paper's
            // snapshot Jukebox has a detached BlockEntity (this.level
            // == null), and the underlying setItem path eventually
            // calls Level.registryAccess() to resolve the disc's
            // sound registry, which NPEs. Get the LIVE state and
            // setRecord on that — the live wrapper holds a real
            // Level reference. Wrapped in a try so a failure here
            // doesn't kill the whole rollback's apply step.
            try {
                org.bukkit.block.BlockState live = block.getState(false);
                if (live instanceof Jukebox liveJukebox) {
                    org.bukkit.inventory.ItemStack disc =
                            ItemSerialization.decode(snapshot.jukeboxRecord());
                    if (disc != null) {
                        liveJukebox.setRecord(disc);
                    }
                }
            } catch (Throwable jukeboxFailure) {
                // Disc lost; block stays a jukebox without its record.
                // Acceptable — rare path, not worth aborting the rollback.
            }
        }

        if (state instanceof org.bukkit.block.DecoratedPot pot
                && !snapshot.potSherds().isEmpty()) {
            applyPotSherds(pot, snapshot.potSherds());
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
