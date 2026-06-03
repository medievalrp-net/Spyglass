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
    private RollbackPhysicsBlocker physicsBlocker;

    // Per-tick budget for the apply loop, in ms. Sourced from
    // limits.rollback-tick-budget-ms. 45 ms ate 90% of every tick
    // and tanked TPS on big rollbacks; 15 ms keeps the server
    // responsive at the cost of wall time.
    private volatile long tickBudgetMs = 15L;

    // Off-main executor for the per-block palette writes. When set,
    // the bulk setBlockState loop runs here while the main thread
    // only handles tile-entity state and the chunk-update packet.
    // PalettedContainer is per-section locked so concurrent reads
    // from the main thread stay consistent. Null = sync fallback.
    private volatile java.util.concurrent.Executor worldWriteExecutor;

    // Parsed BlockData is shared across rollbacks. createBlockData is
    // ~5-10us per call and BlockData is immutable, so caching one
    // instance per unique spec string is a big win on the hot loop.
    private static final java.util.concurrent.ConcurrentHashMap<String, org.bukkit.block.data.BlockData> BLOCK_DATA_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static org.bukkit.block.data.BlockData blockDataFor(String spec) {
        org.bukkit.block.data.BlockData cached = BLOCK_DATA_CACHE.get(spec);
        if (cached != null) {
            return cached;
        }
        org.bukkit.block.data.BlockData parsed = Bukkit.createBlockData(spec);
        org.bukkit.block.data.BlockData prior = BLOCK_DATA_CACHE.putIfAbsent(spec, parsed);
        return prior == null ? parsed : prior;
    }

    public RollbackEngine() {
        this(null, null);
    }

    // With a non-null recorder/support, every applied effect also
    // emits a lightweight ROLLBACK record so the wand reads
    // "ROLLBACK placed STONE" on the rolled block.
    public RollbackEngine(Recorder recorder, RecordingSupport support) {
        this.recorder = recorder;
        this.support = support;
    }

    public void setCustomEffectLookup(java.util.function.Function<String, java.util.Optional<RollbackEffectHandler>> lookup) {
        this.handlerLookup = lookup == null
                ? type -> java.util.Optional.empty()
                : lookup;
    }

    public void setPhysicsBlocker(RollbackPhysicsBlocker blocker) {
        this.physicsBlocker = blocker;
    }

    public void setTickBudgetMs(long ms) {
        this.tickBudgetMs = Math.max(1L, ms);
    }

    public void setWorldWriteExecutor(java.util.concurrent.Executor exec) {
        this.worldWriteExecutor = exec;
    }

    // Holds a plugin chunk ticket per (world, cx, cz) while the
    // worker thread writes the chunk. Without it the chunk system
    // can unload between our main-thread snapshot and the worker's
    // writes, silently no-opping them.
    private volatile org.bukkit.plugin.Plugin chunkTicketHolder;

    public void setChunkTicketHolder(org.bukkit.plugin.Plugin plugin) {
        this.chunkTicketHolder = plugin;
    }

    // Synchronous entry point for tests. Production uses applyAllChunked
    // directly so the apply loop can yield between ticks.
    public List<RollbackResult> applyAll(List<RollbackEffect> effects, CommandSender sender) {
        return applyAllChunked(effects, sender, ServiceSupport.synchronous(), Integer.MAX_VALUE).join();
    }

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

        // Stage 1 is pure compute (filter, bbox, sort) and runs off
        // the main thread when an executor is wired. Stage 2 touches
        // Bukkit and hops back to the server thread.
        Runnable stageOne = () -> {
            List<Integer> blockReplaceIndices = new ArrayList<>();
            List<RollbackEffect.BlockReplace> blockReplaceEffects = new ArrayList<>();
            for (int index = 0; index < effects.size(); index++) {
                if (effects.get(index) instanceof RollbackEffect.BlockReplace br) {
                    blockReplaceIndices.add(index);
                    blockReplaceEffects.add(br);
                }
            }

            if (physicsBlocker != null && !blockReplaceEffects.isEmpty()) {
                int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
                int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
                UUID worldId = blockReplaceEffects.get(0).location().worldId();
                for (RollbackEffect.BlockReplace br : blockReplaceEffects) {
                    BlockLocation l = br.location();
                    if (l.x() < minX) minX = l.x();
                    if (l.y() < minY) minY = l.y();
                    if (l.z() < minZ) minZ = l.z();
                    if (l.x() > maxX) maxX = l.x();
                    if (l.y() > maxY) maxY = l.y();
                    if (l.z() > maxZ) maxZ = l.z();
                }
                long handle = physicsBlocker.enter(worldId, minX, minY, minZ, maxX, maxY, maxZ);
                done.whenComplete((r, t) -> physicsBlocker.exit(handle));
            }

            sortParallelByChunk(blockReplaceIndices, blockReplaceEffects);

            scheduler.onMainThread(() -> applyChunkByChunk(0, effects, resultArray,
                    blockReplaceIndices, blockReplaceEffects,
 sender, scheduler, batchSize, done, cancelFlag));
        };

        if (worldWriteExecutor != null) {
            java.util.concurrent.CompletableFuture.runAsync(stageOne, worldWriteExecutor);
        } else {
            stageOne.run();
        }
        return done;
    }

    // Group entries by chunk and apply low Y first within each chunk.
    // Bottom-up matters: if we restore gravel before its support block,
    // the gravity check on the next tick turns the gravel back into a
    // falling entity.
    private static void sortParallelByChunk(List<Integer> indices,
                                            List<RollbackEffect.BlockReplace> effects) {
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
            c = Integer.compare(la.y(), lb.y());
            if (c != 0) return c;
            return Integer.compare(a, b);
        });
        Integer[] newIndices = new Integer[n];
        @SuppressWarnings("unchecked")
        RollbackEffect.BlockReplace[] newEffects = new RollbackEffect.BlockReplace[n];
        for (int i = 0; i < n; i++) {
            newIndices[i] = indices.get(order[i]);
            newEffects[i] = effects.get(order[i]);
        }
        for (int i = 0; i < n; i++) {
            indices.set(i, newIndices[i]);
            effects.set(i, newEffects[i]);
        }
    }

    // Walk chunks until the tick budget is spent, then yield. Per
    // chunk: write blocks via setBlockData with physics off (gravity
    // ticks suppressed by RollbackPhysicsBlocker), then apply
    // tile-entity state for containers/signs/banners/etc.
    private void applyChunkByChunk(int from,
                                   List<RollbackEffect> effects,
                                   RollbackResult[] resultArray,
                                   List<Integer> blockReplaceIndices,
                                   List<RollbackEffect.BlockReplace> blockReplaceEffects,
                                   CommandSender sender,
                                   ServiceSupport scheduler,
                                   int batchSize,
                                   CompletableFuture<List<RollbackResult>> done,
                                   java.util.concurrent.atomic.AtomicBoolean cancelFlag) {
        int total = blockReplaceIndices.size();
        // Cancellation check happens between chunks, never mid-chunk.
        // Anything not yet touched gets marked Skipped so the result
        // array stays dense for the summary.
        if (cancelFlag.get()) {
            for (int j = from; j < total; j++) {
                int targetIndex = blockReplaceIndices.get(j);
                if (resultArray[targetIndex] == null) {
                    resultArray[targetIndex] = new RollbackResult.Skipped(
                            blockReplaceEffects.get(j),
                            new RollbackReason.Error("Cancelled by operator"));
                }
            }
            done.complete(List.of(resultArray));
            return;
        }
        if (from >= total) {
            runContainerAndLeftover(effects, resultArray, sender, scheduler, batchSize, done);
            return;
        }

        if (worldWriteExecutor != null) {
            applyOneChunkAsync(from, effects, resultArray, blockReplaceIndices,
                    blockReplaceEffects, 
                    sender, scheduler, batchSize, done, cancelFlag);
            return;
        }

        long tickStart = System.nanoTime();
        long budgetNanos = tickBudgetMs * 1_000_000L;
        int blocksThisTick = 0;
        int i = from;

        while (i < total) {
            BlockLocation startLoc = blockReplaceEffects.get(i).location();
            UUID worldId = startLoc.worldId();
            int cx = startLoc.x() >> 4;
            int cz = startLoc.z() >> 4;
            int chunkEnd = i + 1;
            while (chunkEnd < total) {
                BlockLocation l = blockReplaceEffects.get(chunkEnd).location();
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
                    int targetIndex = blockReplaceIndices.get(j);
                    RollbackEffect.BlockReplace eff = blockReplaceEffects.get(j);
                    resultArray[targetIndex] = new RollbackResult.Skipped(eff,
                            new RollbackReason.InvalidLocation(eff.location()));
                }
            } else {
                // Resolve serverLevel + levelChunk once per chunk so
                // writeBlock only re-resolves the section on Y crossings.
                ChunkDirectWriter.ChunkContext chunkCtx =
                        ChunkDirectWriter.prepareChunk(world, cx, cz);

                for (int j = i; j < chunkEnd; j++) {
                    int targetIndex = blockReplaceIndices.get(j);
                    RollbackEffect.BlockReplace effect = blockReplaceEffects.get(j);
                    BlockSnapshot replacement = effect.replacement();
                    BlockLocation loc = effect.location();
                    try {
                        org.bukkit.block.data.BlockData bd =
                                blockDataFor(replacement.blockData());
                        if (chunkCtx != null) {
                            chunkCtx.writeBlock(loc.x(), loc.y(), loc.z(), bd);
                        } else {
                            ChunkDirectWriter.writeBlock(world, loc.x(), loc.y(), loc.z(), bd);
                        }
                        if (!replacement.simple()) {
                            Block block = world.getBlockAt(loc.x(), loc.y(), loc.z());
                            applyTileEntityState(block, replacement);
                        }
                        RollbackEffect inverse = new RollbackEffect.BlockReplace(
                                loc, replacement, effect.expectedCurrent());
                        resultArray[targetIndex] = new RollbackResult.Applied(effect, inverse);
                    } catch (RuntimeException thrown) {
                        resultArray[targetIndex] = new RollbackResult.Skipped(effect,
                                new RollbackReason.Error("Unhandled error: " + thrown.getMessage()));
                    }
                }
                ChunkDirectWriter.finishChunk(chunkCtx);
                // Direct NMS writes skip the per-block packet queue,
                // so push the new chunk to viewers ourselves.
                try {
                    ChunkResender.resend(world, cx, cz);
                } catch (RuntimeException ignored) {
                }
            }

            blocksThisTick += chunkEnd - i;
            i = chunkEnd;

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
                    next, effects, resultArray, blockReplaceIndices, blockReplaceEffects,
                    sender, scheduler, batchSize, done, cancelFlag));
        } else {
            runContainerAndLeftover(effects, resultArray, sender, scheduler, batchSize, done);
        }
    }

    // Off-main variant of applyChunkByChunk. Main thread enters and
    // walks to the chunk boundary, then the worker runs the bulk
    // setBlockState loop, then we hop back to main for tile-entity
    // state and the chunk packet.
    private void applyOneChunkAsync(int from,
                                    List<RollbackEffect> effects,
                                    RollbackResult[] resultArray,
                                    List<Integer> blockReplaceIndices,
                                    List<RollbackEffect.BlockReplace> blockReplaceEffects,
                                    CommandSender sender,
                                    ServiceSupport scheduler,
                                    int batchSize,
                                    CompletableFuture<List<RollbackResult>> done,
                                    java.util.concurrent.atomic.AtomicBoolean cancelFlag) {
        int total = blockReplaceIndices.size();
        final org.bukkit.plugin.Plugin ticketHolder = chunkTicketHolder;
        java.util.concurrent.CompletableFuture
                .supplyAsync(() -> {
                    BlockLocation startLoc = blockReplaceEffects.get(from).location();
                    UUID worldId = startLoc.worldId();
                    int cx = startLoc.x() >> 4;
                    int cz = startLoc.z() >> 4;
                    int chunkEnd = from + 1;
                    while (chunkEnd < total) {
                        BlockLocation l = blockReplaceEffects.get(chunkEnd).location();
                        if (!l.worldId().equals(worldId)
                                || (l.x() >> 4) != cx
                                || (l.z() >> 4) != cz) {
                            break;
                        }
                        chunkEnd++;
                    }

                    World world = Bukkit.getWorld(worldId);
                    if (world == null) {
                        // Worker writes to resultArray are visible
                        // on main after future.complete (happens-before).
                        for (int j = from; j < chunkEnd; j++) {
                            int targetIndex = blockReplaceIndices.get(j);
                            RollbackEffect.BlockReplace eff = blockReplaceEffects.get(j);
                            resultArray[targetIndex] = new RollbackResult.Skipped(eff,
                                    new RollbackReason.InvalidLocation(eff.location()));
                        }
                        return new ChunkPipelineResult(null, cx, cz, chunkEnd, null, null, false, null);
                    }

                    int chunkSize = chunkEnd - from;
                    org.bukkit.block.data.BlockData[] writes =
                            new org.bukkit.block.data.BlockData[chunkSize];
                    for (int j = 0; j < chunkSize; j++) {
                        try {
                            writes[j] = blockDataFor(
                                    blockReplaceEffects.get(from + j).replacement().blockData());
                        } catch (RuntimeException thrown) {
                            writes[j] = null;
                        }
                    }

                    // Pin the chunk loaded for this chunk's apply.
                    // addPluginChunkTicket is thread-safe per Paper.
                    boolean ticketAdded = false;
                    if (ticketHolder != null) {
                        try {
                            ticketAdded = world.addPluginChunkTicket(cx, cz, ticketHolder);
                        } catch (Throwable ignored) {
                        }
                    }

                    // Do the isSimple check and Applied/inverse alloc
                    // here on the worker so main thread only handles
                    // the small slice of blocks with tile-entity state.
                    ChunkDirectWriter.ChunkContext ctx =
                            ChunkDirectWriter.prepareChunk(world, cx, cz);
                    java.util.BitSet nonSimpleMask = new java.util.BitSet(chunkSize);
                    for (int j = 0; j < chunkSize; j++) {
                        int targetIndex = blockReplaceIndices.get(from + j);
                        RollbackEffect.BlockReplace effect = blockReplaceEffects.get(from + j);
                        if (writes[j] == null) {
                            resultArray[targetIndex] = new RollbackResult.Skipped(effect,
                                    new RollbackReason.Error("Unparseable blockdata"));
                            continue;
                        }
                        BlockLocation loc = effect.location();
                        BlockSnapshot replacement = effect.replacement();
                        if (ctx != null) {
                            ctx.writeBlock(loc.x(), loc.y(), loc.z(), writes[j]);
                        }
                        if (replacement.simple()) {
                            // No tile entity to apply; palette write
                            // is the whole apply. Build Applied here.
                            RollbackEffect inverse = new RollbackEffect.BlockReplace(
                                    loc, replacement, effect.expectedCurrent());
                            resultArray[targetIndex] = new RollbackResult.Applied(effect, inverse);
                        } else {
                            // Needs BlockState.update on main thread.
                            nonSimpleMask.set(j);
                        }
                    }

                    return new ChunkPipelineResult(world, cx, cz, chunkEnd, ctx, writes, ticketAdded, nonSimpleMask);
                }, worldWriteExecutor)
                .whenComplete((result, throwable) -> scheduler.onMainThread(() -> {
                    if (result == null || result.world() == null) {
                        // Worker threw or marked the chunk skipped.
                        int next = result != null ? result.chunkEnd() : from + 1;
                        scheduleNextChunk(next, effects, resultArray, blockReplaceIndices,
                                blockReplaceEffects, 
                                sender, scheduler, batchSize, done, cancelFlag);
                        return;
                    }
                    World world = result.world();
                    int cx = result.cx();
                    int cz = result.cz();
                    int chunkEnd = result.chunkEnd();
                    ChunkDirectWriter.ChunkContext ctx = result.ctx();
                    org.bukkit.block.data.BlockData[] writes = result.writes();
                    java.util.BitSet nonSimpleMask = result.nonSimpleMask();
                    try {
                        // Worker already handled every simple block.
                        // Only iterate the ones that need tile-entity
                        // state applied here.
                        for (int j = nonSimpleMask.nextSetBit(0); j >= 0;
                                j = nonSimpleMask.nextSetBit(j + 1)) {
                            int targetIndex = blockReplaceIndices.get(from + j);
                            RollbackEffect.BlockReplace effect = blockReplaceEffects.get(from + j);
                            BlockSnapshot replacement = effect.replacement();
                            BlockLocation loc = effect.location();
                            try {
                                if (ctx == null) {
                                    // Worker's prepareChunk failed; fall
                                    // back to a single-block write here.
                                    ChunkDirectWriter.writeBlock(
                                            world, loc.x(), loc.y(), loc.z(), writes[j]);
                                }
                                Block block = world.getBlockAt(loc.x(), loc.y(), loc.z());
                                applyTileEntityState(block, replacement);
                                RollbackEffect inverse = new RollbackEffect.BlockReplace(
                                        loc, replacement, effect.expectedCurrent());
                                resultArray[targetIndex] = new RollbackResult.Applied(effect, inverse);
                            } catch (RuntimeException ex) {
                                resultArray[targetIndex] = new RollbackResult.Skipped(effect,
                                        new RollbackReason.Error("Unhandled error: " + ex.getMessage()));
                            }
                        }
                        ChunkDirectWriter.finishChunk(ctx);
                        try {
                            ChunkResender.resend(world, cx, cz);
                        } catch (RuntimeException ignored) {
                        }
                        if (sender instanceof Player p && total > 0) {
                            p.sendActionBar(Component.text("Rolling back " + chunkEnd + " / " + total));
                        }
                    } finally {
                        if (result.ticketAdded() && ticketHolder != null) {
                            try {
                                world.removePluginChunkTicket(cx, cz, ticketHolder);
                            } catch (Throwable ignored) {
                            }
                        }
                        scheduleNextChunk(chunkEnd, effects, resultArray, blockReplaceIndices,
                                blockReplaceEffects, 
                                sender, scheduler, batchSize, done, cancelFlag);
                    }
                }));
    }

    // Carries one chunk's apply state from worker back to main.
    // world is null when the worker bailed (unloaded world); main
    // thread then just advances to the next chunk.
    private record ChunkPipelineResult(
            World world,
            int cx,
            int cz,
            int chunkEnd,
            ChunkDirectWriter.ChunkContext ctx,
            org.bukkit.block.data.BlockData[] writes,
            boolean ticketAdded,
            java.util.BitSet nonSimpleMask) {
    }

    private void scheduleNextChunk(int next,
                                   List<RollbackEffect> effects,
                                   RollbackResult[] resultArray,
                                   List<Integer> blockReplaceIndices,
                                   List<RollbackEffect.BlockReplace> blockReplaceEffects,
                                   CommandSender sender,
                                   ServiceSupport scheduler,
                                   int batchSize,
                                   CompletableFuture<List<RollbackResult>> done,
                                   java.util.concurrent.atomic.AtomicBoolean cancelFlag) {
        scheduler.onMainThreadLater(1L, () -> applyChunkByChunk(
                next, effects, resultArray, blockReplaceIndices, blockReplaceEffects,
                sender, scheduler, batchSize, done, cancelFlag));
    }


    // Containers (one batched apply per chest) then leftover entity
    // ops and custom handlers. Called once the block phase drains.
    private void runContainerAndLeftover(List<RollbackEffect> effects,
                                         RollbackResult[] resultArray,
                                         CommandSender sender,
                                         ServiceSupport scheduler,
                                         int batchSize,
                                         CompletableFuture<List<RollbackResult>> done) {
        // Audit trail: emit a lightweight rolled-place / rolled-break
        // record for every block this rollback restored, so the wand
        // reads "ROLLBACK placed/broke X" and a:rolled-place queries
        // surface what the rollback touched. This method always runs on
        // the main thread, so firing the committed-hook (a Bukkit event)
        // here is safe. recorder/support are null in unit tests, making
        // this a no-op there. Restores the per-block emission Spyglass
        // did before the NMS-direct-section rewrite dropped the call site.
        if (recorder != null && support != null) {
            for (RollbackResult r : resultArray) {
                if (r instanceof RollbackResult.Applied applied
                        && applied.effect() instanceof RollbackEffect.BlockReplace br) {
                    emitRollbackSourceRecord(sender, br.location(), br.replacement());
                }
            }
        }
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

        applyLeftoverBatch(0, effects, resultArray, sender, scheduler, batchSize, done);
    }

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

    // Apply only the tile-entity payload, assuming material and
    // blockdata are already correct.
    private void applyTileEntityState(Block block, BlockSnapshot snapshot) {
        applyTilePayload(block, block.getState(), snapshot);
    }

    // Apply the tile-entity payload — container items, sign text, banner
    // patterns, jukebox disc, decorated-pot sherds — onto an already-typed
    // block, then push the state. Shared by applyTileEntityState (block
    // already written by ChunkDirectWriter) and applySnapshot (which sets
    // material + blockdata first).
    private void applyTilePayload(Block block, BlockState state, BlockSnapshot snapshot) {
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
            // Paper's snapshot Jukebox has a detached BlockEntity
            // (level == null) and setItem NPEs when it tries to
            // resolve the disc's sound registry. Use the live state
            // instead so we have a real Level reference.
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
            }
        }

        if (state instanceof org.bukkit.block.DecoratedPot pot
                && !snapshot.potSherds().isEmpty()) {
            applyPotSherds(pot, snapshot.potSherds());
        }

        state.update(true, false);
    }

    // Order matches DecoratedPot.Side enum declaration; the snapshot
    // list is built the same way in BlockSnapshots.capture.
    private void applyPotSherds(org.bukkit.block.DecoratedPot pot, List<String> names) {
        org.bukkit.block.DecoratedPot.Side[] sides = org.bukkit.block.DecoratedPot.Side.values();
        for (int i = 0; i < sides.length && i < names.size(); i++) {
            try {
                Material material = Material.valueOf(names.get(i));
                pot.setSherd(sides[i], material);
            } catch (IllegalArgumentException ignored) {
                // Material name gone in this MC version; leave default.
            }
        }
    }

    // Apply every slot write for one location in one getState/update
    // cycle. Per-slot precondition checks still run individually.
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

    // Emit a lightweight wand-attribution record on a rolled block so
    // searches show "ROLLBACK placed STONE". BlockUseRecord avoids
    // carrying the before/after snapshot blobs that BlockPlaceRecord
    // would, which mattered: at 150 blocks the snapshot pairs combined
    // with FAWE's write buffer were enough to tip the heap into OOM.
    private void emitRollbackSourceRecord(CommandSender sender, BlockLocation location, BlockSnapshot after) {
        if (recorder == null || support == null) {
            return;
        }
        String operatorName = sender == null ? "console" : sender.getName();
        Instant occurred = support.now();
        Source source = Source.environment("ROLLBACK");
        Origin origin = Origin.rollback(operatorName);
        // Skip support.context (which uses SecureRandom) to avoid
        // entropy reads on the main thread for every rolled block.
        RecordContext ctx = new RecordContext(
                RecordingSupport.fastRandomUUID(),
                occurred,
                support.expiresAt(occurred),
                origin, source, location, support.serverName());
        boolean toAir = after == null || after.material() == Material.AIR;
        // Lowercase-hyphenated form matches shulker-open etc. and
        // dodges the case-sensitivity quirk in EventCatalog.recordClassOf.
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

        // Some Bukkit inventory events fire with slot = -1 (drag /
        // auto-stack) and the recorded container may be wider than
        // the live one (chest -> hopper downgrade). Range-check
        // before getItem to avoid IndexOutOfBoundsException.
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
        applyTilePayload(block, state, snapshot);
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
