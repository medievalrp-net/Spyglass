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
import net.medievalrp.spyglass.api.event.EventRecord;
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

    private static final java.util.logging.Logger LOGGER =
            java.util.logging.Logger.getLogger(RollbackEngine.class.getName());

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

    // How many chunks' palette writes the apply path dispatches
    // concurrently per batch — matched to the worldWriteExecutor pool
    // size so every thread stays busy without queueing. 1 keeps the
    // legacy one-chunk-at-a-time behaviour (single-thread executor).
    private volatile int worldWriteParallelism = 1;

    // How many chunks to resolve + dispatch per barrier. Decoupled from
    // the pool size: a larger batch queues on the pool (pool-size run at
    // once) and costs one main-thread hop. Measured: hop cost is cheap,
    // so this stays modest to keep the per-tick main post (~1ms/chunk)
    // well under the 50ms tick budget. The dominant apply cost was the
    // per-block audit-record build, which now runs off-main.
    private volatile int worldWriteBatchChunks = 16;

    // Apply-phase breakdown (reset per applyAllChunked, logged on
    // completion). writeNanos = wall-time the pool spends on palette
    // writes (sum of per-batch barrier waits); postNanos = main-thread
    // tile-entity/finish/resend; resolveNanos = main-thread getChunk +
    // prepareChunk; batches = barrier count; the hop latency (idle ticks
    // between batches) is total - write - post - resolve.
    private final java.util.concurrent.atomic.AtomicLong applyResolveNanos =
            new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong applyWriteNanos =
            new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong applyPostNanos =
            new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicInteger applyBatchCount =
            new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger applyChunkApplies =
            new java.util.concurrent.atomic.AtomicInteger();

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

    // Number of pool threads available for concurrent palette writes.
    // Set to the worldWriteExecutor pool size by the plugin; clamped to >= 1.
    public void setWorldWriteParallelism(int parallelism) {
        this.worldWriteParallelism = Math.max(1, parallelism);
    }

    // How many chunks to resolve + dispatch per main-thread hop. Larger
    // = fewer tick-deferred hops (the dominant apply cost) at the price
    // of a bigger per-tick post. Clamped to >= 1.
    public void setWorldWriteBatchChunks(int chunks) {
        this.worldWriteBatchChunks = Math.max(1, chunks);
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

        // Reset + log the apply-phase breakdown for this run.
        applyResolveNanos.set(0L);
        applyWriteNanos.set(0L);
        applyPostNanos.set(0L);
        applyBatchCount.set(0);
        applyChunkApplies.set(0);
        long applyStart = System.nanoTime();
        done.whenComplete((r, t) -> {
            long totalMs = (System.nanoTime() - applyStart) / 1_000_000L;
            long resolveMs = applyResolveNanos.get() / 1_000_000L;
            long writeMs = applyWriteNanos.get() / 1_000_000L;
            long postMs = applyPostNanos.get() / 1_000_000L;
            int batches = applyBatchCount.get();
            int chunkApplies = applyChunkApplies.get();
            // Only the substantive pages — skip the small tail page so the
            // log isn't spammed on routine rollbacks.
            if (totalMs >= 200L) {
                LOGGER.info(String.format(
                        "Spyglass apply breakdown: resolve=%dms write=%dms post=%dms hop/idle=%dms"
                                + " | %d batches, %d chunk-applies, %dms total",
                        resolveMs, writeMs, postMs,
                        Math.max(0L, totalMs - resolveMs - writeMs - postMs),
                        batches, chunkApplies, totalMs));
            }
        });

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
    // Package-private for the equivalence test.
    static void sortParallelByChunk(List<Integer> indices,
                                    List<RollbackEffect.BlockReplace> effects) {
        int n = indices.size();
        if (n <= 1) {
            return;
        }
        // Fast path: single world with a coordinate span small enough to
        // pack (relCx, relCz, y, originalIndex) into one long, then sort a
        // primitive long[]. No Integer boxing and no per-comparison
        // List.get / location() / UUID compare — on a ~600K-effect page
        // the boxed comparator sort was the dominant, GC-spiking cost of
        // the apply. The original index in the low bits is a stable
        // tie-break identical to the comparator's Integer.compare(a, b).
        // Multi-world or a span too wide to pack falls back to the
        // comparator sort below (same ordering, just slower).
        UUID world = effects.get(0).location().worldId();
        int minCx = Integer.MAX_VALUE, maxCx = Integer.MIN_VALUE;
        int minCz = Integer.MAX_VALUE, maxCz = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        boolean singleWorld = true;
        for (int i = 0; i < n; i++) {
            BlockLocation l = effects.get(i).location();
            if (!l.worldId().equals(world)) {
                singleWorld = false;
                break;
            }
            int cx = l.x() >> 4, cz = l.z() >> 4, y = l.y();
            if (cx < minCx) minCx = cx;
            if (cx > maxCx) maxCx = cx;
            if (cz < minCz) minCz = cz;
            if (cz > maxCz) maxCz = cz;
            if (y < minY) minY = y;
            if (y > maxY) maxY = y;
        }
        if (singleWorld) {
            int cxBits = bitsFor((long) maxCx - minCx);
            int czBits = bitsFor((long) maxCz - minCz);
            int yBits = bitsFor((long) maxY - minY);
            int idxBits = bitsFor(n - 1);
            if (cxBits + czBits + yBits + idxBits <= 62) {
                int yPos = idxBits;
                int czPos = yPos + yBits;
                int cxPos = czPos + czBits;
                long idxMask = (1L << idxBits) - 1L;
                long[] composite = new long[n];
                for (int i = 0; i < n; i++) {
                    BlockLocation l = effects.get(i).location();
                    long relCx = (long) ((l.x() >> 4) - minCx);
                    long relCz = (long) ((l.z() >> 4) - minCz);
                    long relY = (long) (l.y() - minY);
                    composite[i] = (relCx << cxPos) | (relCz << czPos) | (relY << yPos) | (long) i;
                }
                java.util.Arrays.sort(composite);
                int[] order = new int[n];
                for (int i = 0; i < n; i++) {
                    order[i] = (int) (composite[i] & idxMask);
                }
                applyOrder(indices, effects, order);
                return;
            }
        }
        sortByComparator(indices, effects);
    }

    // Bits needed to hold values in [0, span]; 0 when span <= 0.
    private static int bitsFor(long span) {
        return span <= 0L ? 0 : 64 - Long.numberOfLeadingZeros(span);
    }

    private static void sortByComparator(List<Integer> indices,
                                         List<RollbackEffect.BlockReplace> effects) {
        int n = indices.size();
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
        int[] o = new int[n];
        for (int i = 0; i < n; i++) o[i] = order[i];
        applyOrder(indices, effects, o);
    }

    private static void applyOrder(List<Integer> indices,
                                   List<RollbackEffect.BlockReplace> effects, int[] order) {
        int n = order.length;
        Integer[] newIndices = new Integer[n];
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
            applyChunkBatchParallel(from, effects, resultArray, blockReplaceIndices,
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

    // Parallel variant of applyChunkByChunk. The main thread groups the
    // next N chunks (N = worldWriteParallelism), resolves each chunk's
    // write context up front — getChunk MUST stay on main, the chunk
    // system isn't safe for concurrent off-main access — then fans the
    // per-chunk palette writes across the worldWriteExecutor pool. Once
    // all N finish it hops back to main once for tile-entity state,
    // finishChunk, and the chunk packets, then advances to the next
    // batch.
    //
    // Why this is the throughput win: distinct chunks write to
    // independent LevelChunkSection palettes, and the 4-arg
    // setBlockState takes the useLocks=true path, so N threads writing N
    // different chunks never race. The bulk palette loop — the dominant
    // phase of a large rollback — now scales with core count instead of
    // running single-file on one worker. The main thread is idle while
    // the pool writes, so TPS stays at ~20.
    private void applyChunkBatchParallel(int from,
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
        int parallelism = Math.max(1, worldWriteParallelism);

        // Resolve up to `parallelism` chunks. getChunk + prepareChunk +
        // addPluginChunkTicket all run here on the main thread; the
        // workers only touch the per-section palette afterwards.
        int maxBatchChunks = Math.max(parallelism, worldWriteBatchChunks);
        long tResolve = System.nanoTime();
        List<ChunkWork> batch = new ArrayList<>(Math.min(maxBatchChunks, 64));
        int cursor = from;
        while (cursor < total && batch.size() < maxBatchChunks) {
            BlockLocation startLoc = blockReplaceEffects.get(cursor).location();
            UUID worldId = startLoc.worldId();
            int cx = startLoc.x() >> 4;
            int cz = startLoc.z() >> 4;
            int chunkEnd = cursor + 1;
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
                for (int j = cursor; j < chunkEnd; j++) {
                    int targetIndex = blockReplaceIndices.get(j);
                    RollbackEffect.BlockReplace eff = blockReplaceEffects.get(j);
                    resultArray[targetIndex] = new RollbackResult.Skipped(eff,
                            new RollbackReason.InvalidLocation(eff.location()));
                }
                cursor = chunkEnd;
                continue;
            }

            // Pin the chunk loaded, then resolve its section context —
            // both on main, so the worker never calls into the chunk
            // system. addPluginChunkTicket is thread-safe per Paper.
            boolean ticketAdded = false;
            if (ticketHolder != null) {
                try {
                    ticketAdded = world.addPluginChunkTicket(cx, cz, ticketHolder);
                } catch (Throwable ignored) {
                }
            }
            ChunkDirectWriter.ChunkContext ctx =
                    ChunkDirectWriter.prepareChunk(world, cx, cz);
            batch.add(new ChunkWork(world, cx, cz, cursor, chunkEnd, ctx, ticketAdded));
            cursor = chunkEnd;
        }
        applyResolveNanos.addAndGet(System.nanoTime() - tResolve);

        // Whole scan was unloaded-world chunks (already marked Skipped).
        if (batch.isEmpty()) {
            if (cursor < total) {
                applyChunkByChunk(cursor, effects, resultArray, blockReplaceIndices,
                        blockReplaceEffects, sender, scheduler, batchSize, done, cancelFlag);
            } else {
                runContainerAndLeftover(effects, resultArray, sender, scheduler, batchSize, done);
            }
            return;
        }

        final int batchEnd = cursor;
        final int batchChunks = batch.size();
        final long tDispatch = System.nanoTime();
        // Fan the palette writes across the pool — one future per chunk.
        @SuppressWarnings("unchecked")
        CompletableFuture<Void>[] futures = new CompletableFuture[batch.size()];
        for (int b = 0; b < batch.size(); b++) {
            ChunkWork work = batch.get(b);
            futures[b] = java.util.concurrent.CompletableFuture.runAsync(
                    () -> writeChunkPalettes(work, resultArray, blockReplaceIndices, blockReplaceEffects),
                    worldWriteExecutor);
        }

        java.util.concurrent.CompletableFuture.allOf(futures)
                .whenComplete((v, throwable) -> {
                    applyWriteNanos.addAndGet(System.nanoTime() - tDispatch);
                    applyBatchCount.incrementAndGet();
                    applyChunkApplies.addAndGet(batchChunks);
                    scheduler.onMainThread(() -> {
                    // Main-thread post for every chunk in the batch: the
                    // heavy palette writes already happened off-main, so
                    // this is just tile-entity state, finishChunk, the
                    // chunk packet, and the ticket release.
                    long tPost = System.nanoTime();
                    for (ChunkWork work : batch) {
                        applyChunkPostMain(work, resultArray, blockReplaceIndices, blockReplaceEffects);
                    }
                    applyPostNanos.addAndGet(System.nanoTime() - tPost);
                    if (sender instanceof Player p && total > 0) {
                        p.sendActionBar(Component.text("Rolling back " + batchEnd + " / " + total));
                    }
                    if (batchEnd < total) {
                        applyChunkByChunk(batchEnd, effects, resultArray, blockReplaceIndices,
                                blockReplaceEffects, sender, scheduler, batchSize, done, cancelFlag);
                    } else {
                        runContainerAndLeftover(effects, resultArray, sender, scheduler, batchSize, done);
                    }
                    });
                });
    }

    // Worker-thread half: parse blockdata and write every block's palette
    // entry. No chunk-system access (getChunk already ran on main) — only
    // the locked LevelChunkSection.setBlockState. Records non-simple
    // blocks in the work unit's mask for main-thread tile-entity handling.
    private void writeChunkPalettes(ChunkWork work,
                                    RollbackResult[] resultArray,
                                    List<Integer> blockReplaceIndices,
                                    List<RollbackEffect.BlockReplace> blockReplaceEffects) {
        int from = work.from;
        int chunkSize = work.chunkEnd - from;
        org.bukkit.block.data.BlockData[] writes =
                new org.bukkit.block.data.BlockData[chunkSize];
        java.util.BitSet nonSimpleMask = new java.util.BitSet(chunkSize);
        for (int j = 0; j < chunkSize; j++) {
            int targetIndex = blockReplaceIndices.get(from + j);
            RollbackEffect.BlockReplace effect = blockReplaceEffects.get(from + j);
            org.bukkit.block.data.BlockData bd;
            try {
                bd = blockDataFor(effect.replacement().blockData());
            } catch (RuntimeException thrown) {
                bd = null;
            }
            writes[j] = bd;
            if (bd == null) {
                resultArray[targetIndex] = new RollbackResult.Skipped(effect,
                        new RollbackReason.Error("Unparseable blockdata"));
                continue;
            }
            BlockLocation loc = effect.location();
            BlockSnapshot replacement = effect.replacement();
            if (work.ctx == null) {
                // Degraded path (prepareChunk failed): route EVERY cell to
                // the main-thread half, which writes via the single-block
                // fallback. Simple cells previously reported Applied here
                // without any write ever happening.
                nonSimpleMask.set(j);
                continue;
            }
            // Force-overwrite (#69): restore the recorded block regardless
            // of the live state — matching the original Spyglass and
            // CoreProtect, and the documented contract in
            // RollbackEngineChaosTest. A grief rollback must put the block
            // back even if water/lava/fire/falling-blocks drifted into the
            // gap after the edit; those changes are unlogged, so nothing
            // should protect them. Cross-actor cases are handled by scoping
            // the rollback, not by a per-cell live-state guard.
            work.ctx.writeBlock(loc.x(), loc.y(), loc.z(), bd);
            if (replacement.simple()) {
                // No tile entity to apply; the palette write is the whole
                // apply. Build Applied here on the worker.
                RollbackEffect inverse = new RollbackEffect.BlockReplace(
                        loc, replacement, effect.expectedCurrent());
                resultArray[targetIndex] = new RollbackResult.Applied(effect, inverse);
            } else {
                // Needs BlockState.update on the main thread.
                nonSimpleMask.set(j);
            }
        }
        work.writes = writes;
        work.nonSimpleMask = nonSimpleMask;
    }

    // Main-thread half: apply tile-entity state for the non-simple blocks
    // (containers/signs/etc need a BlockState.update on main), mark the
    // chunk saved, push the chunk packet, release the ticket.
    private void applyChunkPostMain(ChunkWork work,
                                    RollbackResult[] resultArray,
                                    List<Integer> blockReplaceIndices,
                                    List<RollbackEffect.BlockReplace> blockReplaceEffects) {
        World world = work.world;
        int from = work.from;
        java.util.BitSet nonSimpleMask = work.nonSimpleMask;
        org.bukkit.block.data.BlockData[] writes = work.writes;
        try {
            if (nonSimpleMask != null) {
                for (int j = nonSimpleMask.nextSetBit(0); j >= 0;
                        j = nonSimpleMask.nextSetBit(j + 1)) {
                    int targetIndex = blockReplaceIndices.get(from + j);
                    RollbackEffect.BlockReplace effect = blockReplaceEffects.get(from + j);
                    BlockSnapshot replacement = effect.replacement();
                    BlockLocation loc = effect.location();
                    try {
                        if (work.ctx == null) {
                            // prepareChunk failed for this chunk; force-write
                            // the recorded block here on main (#69 — no
                            // live-state guard, same as the fast path).
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
            }
            ChunkDirectWriter.finishChunk(work.ctx);
            // Direct NMS writes skip the per-block packet queue, so push
            // the new chunk to viewers ourselves.
            try {
                ChunkResender.resend(world, work.cx, work.cz);
            } catch (RuntimeException ignored) {
            }
        } finally {
            if (work.ticketAdded && chunkTicketHolder != null) {
                try {
                    world.removePluginChunkTicket(work.cx, work.cz, chunkTicketHolder);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    // One chunk's apply state, handed from the main-thread resolve phase
    // to the worker (palette writes) and back to main (tile entities +
    // packet). writes/nonSimpleMask are filled by the worker and read on
    // main after allOf completes — the future establishes happens-before;
    // volatile is belt-and-suspenders.
    private static final class ChunkWork {
        final World world;
        final int cx;
        final int cz;
        final int from;
        final int chunkEnd;
        final ChunkDirectWriter.ChunkContext ctx;
        final boolean ticketAdded;
        volatile org.bukkit.block.data.BlockData[] writes;
        volatile java.util.BitSet nonSimpleMask;

        ChunkWork(World world, int cx, int cz, int from, int chunkEnd,
                  ChunkDirectWriter.ChunkContext ctx, boolean ticketAdded) {
            this.world = world;
            this.cx = cx;
            this.cz = cz;
            this.from = from;
            this.chunkEnd = chunkEnd;
            this.ctx = ctx;
            this.ticketAdded = ticketAdded;
        }
    }

    // ----- Columnar apply (#67) -----------------------------------------
    // Allocation-lean apply for simple block-replaces: reads coordinates and
    // block-data from primitive BlockColumns + a shared palette instead of a
    // List<RollbackEffect.BlockReplace>, and reports counts instead of a
    // RollbackResult object per cell. Same off-main chunk cadence as
    // applyChunkBatchParallel (TPS stays at ~20), but a 2M window's live set
    // is now reference-free primitives that don't promote to old gen under
    // MaxTenuringThreshold=1 — removing the apply-side allocation that, with
    // lean decode, was the last driver of the Mixed-GC rollback freeze.
    // Containers/signs/banners/entities/custom effects stay on the object
    // path (runContainerAndLeftover / applyAllChunked) — they are rare and
    // need per-cell state handling the columnar fast path deliberately omits.

    /** Counts produced by {@link #applyColumnsChunked} — no per-cell objects. */
    @ApiStatus.Internal
    public static final class ApplyCounts {
        public long applied;
        public long blockChanged;
        public long unparseable;
        public long invalidLocation;
        public long cancelled;

        /** Total skipped across every benign and error reason. */
        public long skipped() {
            return blockChanged + unparseable + invalidLocation + cancelled;
        }

        /** Skips that are real failures (parity with RollbackReason.Error). */
        public long errors() {
            return unparseable + cancelled;
        }

        public void add(ApplyCounts other) {
            applied += other.applied;
            blockChanged += other.blockChanged;
            unparseable += other.unparseable;
            invalidLocation += other.invalidLocation;
            cancelled += other.cancelled;
        }
    }

    public CompletableFuture<ApplyCounts> applyColumnsChunked(
            UUID worldId, BlockColumns cols, CommandSender sender,
            ServiceSupport scheduler, int batchSize,
            java.util.concurrent.atomic.AtomicBoolean cancelFlag) {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("RollbackEngine.applyColumnsChunked must run on the main thread.");
        }
        CompletableFuture<ApplyCounts> done = new CompletableFuture<>();
        ApplyCounts counts = new ApplyCounts();
        int total = cols.count();
        if (total == 0) {
            done.complete(counts);
            return done;
        }
        World world = Bukkit.getWorld(worldId);
        if (world == null) {
            counts.invalidLocation += total;
            done.complete(counts);
            return done;
        }
        if (physicsBlocker != null) {
            long handle = physicsBlocker.enter(worldId,
                    cols.minX(), cols.minY(), cols.minZ(),
                    cols.maxX(), cols.maxY(), cols.maxZ());
            done.whenComplete((r, t) -> physicsBlocker.exit(handle));
        }
        // Sort off-main (chunk-grouped, bottom-up Y), then apply on main.
        Runnable stageOne = () -> {
            int[] order = cols.chunkSortedOrder();
            scheduler.onMainThread(() -> applyColumnBatch(
                    world, cols, order, 0, counts, sender, scheduler, batchSize, done, cancelFlag));
        };
        if (worldWriteExecutor != null) {
            java.util.concurrent.CompletableFuture.runAsync(stageOne, worldWriteExecutor);
        } else {
            stageOne.run();
        }
        return done;
    }

    private void applyColumnBatch(World world, BlockColumns cols, int[] order, int from,
                                  ApplyCounts counts, CommandSender sender, ServiceSupport scheduler,
                                  int batchSize, CompletableFuture<ApplyCounts> done,
                                  java.util.concurrent.atomic.AtomicBoolean cancelFlag) {
        int total = cols.count();
        if (cancelFlag.get()) {
            counts.cancelled += total - from;
            done.complete(counts);
            return;
        }
        if (from >= total) {
            done.complete(counts);
            return;
        }
        final org.bukkit.plugin.Plugin ticketHolder = chunkTicketHolder;
        int parallelism = Math.max(1, worldWriteParallelism);
        int maxBatchChunks = Math.max(parallelism, worldWriteBatchChunks);
        // Resolve up to maxBatchChunks chunks on the main thread: contiguous
        // runs of the sorted order share a chunk, so one walk groups them.
        List<ColChunk> batch = new ArrayList<>(Math.min(maxBatchChunks, 64));
        int cursor = from;
        while (cursor < total && batch.size() < maxBatchChunks) {
            int startIdx = order[cursor];
            int cx = cols.x(startIdx) >> 4;
            int cz = cols.z(startIdx) >> 4;
            int rangeEnd = cursor + 1;
            while (rangeEnd < total) {
                int idx = order[rangeEnd];
                if ((cols.x(idx) >> 4) != cx || (cols.z(idx) >> 4) != cz) {
                    break;
                }
                rangeEnd++;
            }
            boolean ticketAdded = false;
            if (ticketHolder != null) {
                try {
                    ticketAdded = world.addPluginChunkTicket(cx, cz, ticketHolder);
                } catch (Throwable ignored) {
                }
            }
            ChunkDirectWriter.ChunkContext ctx = ChunkDirectWriter.prepareChunk(world, cx, cz);
            batch.add(new ColChunk(cx, cz, cursor, rangeEnd, ctx, ticketAdded));
            cursor = rangeEnd;
        }
        final int batchEnd = cursor;

        // Main-thread post: finish/resend each chunk, release tickets, sum
        // the per-chunk counts, then advance. The heavy palette writes
        // already happened off-main (or inline when no executor is wired).
        Runnable afterWrites = () -> scheduler.onMainThread(() -> {
            for (ColChunk cc : batch) {
                if (cc.ctx == null) {
                    // prepareChunk failed: write this chunk's cells on the
                    // main thread via the single-block fallback.
                    writeColumnChunkMain(world, cols, order, cc);
                } else {
                    ChunkDirectWriter.finishChunk(cc.ctx);
                }
                try {
                    ChunkResender.resend(world, cc.cx, cc.cz);
                } catch (RuntimeException ignored) {
                }
                if (cc.ticketAdded && ticketHolder != null) {
                    try {
                        world.removePluginChunkTicket(cc.cx, cc.cz, ticketHolder);
                    } catch (Throwable ignored) {
                    }
                }
                counts.applied += cc.applied;
                counts.blockChanged += cc.blockChanged;
                counts.unparseable += cc.unparseable;
            }
            if (sender instanceof Player p) {
                p.sendActionBar(Component.text("Rolling back " + batchEnd + " / " + total));
            }
            applyColumnBatch(world, cols, order, batchEnd, counts, sender, scheduler, batchSize, done, cancelFlag);
        });

        if (worldWriteExecutor != null) {
            @SuppressWarnings("unchecked")
            CompletableFuture<Void>[] futures = new CompletableFuture[batch.size()];
            for (int b = 0; b < batch.size(); b++) {
                ColChunk cc = batch.get(b);
                futures[b] = java.util.concurrent.CompletableFuture.runAsync(
                        () -> writeColumnChunk(cols, order, cc), worldWriteExecutor);
            }
            java.util.concurrent.CompletableFuture.allOf(futures)
                    .whenComplete((v, t) -> afterWrites.run());
        } else {
            for (ColChunk cc : batch) {
                writeColumnChunk(cols, order, cc);
            }
            afterWrites.run();
        }
    }

    // Worker-thread half: parse + write one chunk's cells from the columns.
    // No chunk-system access (prepareChunk already ran on main) — only the
    // locked LevelChunkSection write. Counts land on the work unit.
    private void writeColumnChunk(BlockColumns cols, int[] order, ColChunk cc) {
        if (cc.ctx == null) {
            return; // handled on the main thread in writeColumnChunkMain
        }
        long applied = 0;
        long changed = 0;
        long unparse = 0;
        for (int k = cc.from; k < cc.rangeEnd; k++) {
            int idx = order[k];
            int x = cols.x(idx);
            int y = cols.y(idx);
            int z = cols.z(idx);
            org.bukkit.block.data.BlockData bd;
            try {
                bd = blockDataFor(cols.replData(idx));
            } catch (RuntimeException thrown) {
                bd = null;
            }
            if (bd == null) {
                unparse++;
                continue;
            }
            // Force-overwrite (#69): write the recorded block regardless of
            // the live state (no conflict guard) — restores grief even where
            // unlogged drift (water/lava/fire/falling) moved into the gap.
            cc.ctx.writeBlock(x, y, z, bd);
            applied++;
        }
        cc.applied = applied;
        cc.blockChanged = changed;
        cc.unparseable = unparse;
    }

    // Main-thread fallback for a chunk whose prepareChunk failed: single
    // block writes, force-overwrite (#69 — no live-state guard).
    private void writeColumnChunkMain(World world, BlockColumns cols, int[] order, ColChunk cc) {
        long applied = 0;
        long unparse = 0;
        for (int k = cc.from; k < cc.rangeEnd; k++) {
            int idx = order[k];
            int x = cols.x(idx);
            int y = cols.y(idx);
            int z = cols.z(idx);
            org.bukkit.block.data.BlockData bd;
            try {
                bd = blockDataFor(cols.replData(idx));
            } catch (RuntimeException thrown) {
                bd = null;
            }
            if (bd == null) {
                unparse++;
                continue;
            }
            ChunkDirectWriter.writeBlock(world, x, y, z, bd);
            applied++;
        }
        cc.applied = applied;
        cc.unparseable = unparse;
    }

    // One chunk's columnar work unit: the [from, rangeEnd) slice of the
    // sorted order that lands in (cx, cz). counts are filled by the worker
    // (ctx != null) or the main fallback (ctx == null) before being summed.
    private static final class ColChunk {
        final int cx;
        final int cz;
        final int from;
        final int rangeEnd;
        final ChunkDirectWriter.ChunkContext ctx;
        final boolean ticketAdded;
        volatile long applied;
        volatile long blockChanged;
        volatile long unparseable;

        ColChunk(int cx, int cz, int from, int rangeEnd,
                 ChunkDirectWriter.ChunkContext ctx, boolean ticketAdded) {
            this.cx = cx;
            this.cz = cz;
            this.from = from;
            this.rangeEnd = rangeEnd;
            this.ctx = ctx;
            this.ticketAdded = ticketAdded;
        }
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
        // surface what the rollback touched. recorder/support are null in
        // unit tests, making this a no-op there. Restores the per-block
        // emission Spyglass did before the NMS-direct-section rewrite
        // dropped the call site.
        //
        // Building one record per restored block (~hundreds of thousands
        // on a big rollback) is pure data assembly — no Bukkit calls once
        // the operator name is captured — so it runs off the main thread.
        // That was the single largest main-thread cost in the apply
        // critical path; recordAll bulk-queues without firing the
        // per-record committed hook, so off-main hand-off is safe and the
        // rollback completes without waiting on it.
        if (recorder != null && support != null) {
            final String operatorName = sender == null ? "console" : sender.getName();
            final RollbackResult[] applied = resultArray;
            Runnable buildAndEmit = () -> {
                List<EventRecord> rolledRecords = new ArrayList<>();
                // Reference reads are atomic; we only act on already-set
                // BlockReplace results (stable after the block phase), so
                // any concurrent leftover-phase writes to other indices
                // are correctly skipped by the instanceof filter.
                for (RollbackResult r : applied) {
                    if (r instanceof RollbackResult.Applied a
                            && a.effect() instanceof RollbackEffect.BlockReplace br) {
                        rolledRecords.add(buildRollbackSourceRecord(
                                operatorName, br.location(), br.replacement()));
                    }
                }
                recorder.recordAll(rolledRecords);
            };
            if (worldWriteExecutor != null) {
                worldWriteExecutor.execute(buildAndEmit);
            } else {
                buildAndEmit.run();
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
    private EventRecord buildRollbackSourceRecord(String operatorName, BlockLocation location, BlockSnapshot after) {
        Instant occurred = support.now();
        Source source = Source.environment("ROLLBACK");
        Origin origin = Origin.rollback(operatorName);
        // Skip support.context (which uses SecureRandom) to avoid
        // entropy reads for every rolled block.
        RecordContext ctx = new RecordContext(
                RecordingSupport.fastRandomUUID(),
                occurred,
                support.expiresAt(occurred),
                origin, source, location, support.serverName(), java.util.Map.of());
        boolean toAir = after == null || after.material() == Material.AIR;
        // Lowercase-hyphenated form matches shulker-open etc. and
        // dodges the case-sensitivity quirk in EventCatalog.recordClassOf.
        String event = toAir ? "rolled-break" : "rolled-place";
        String target = (after == null ? Material.AIR : after.material()).name();
        return new BlockUseRecord(
                ctx.id(), event, ctx.occurred(), ctx.expiresAt(),
                ctx.origin(), ctx.source(), ctx.location(), ctx.server(), target);
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
        Optional<World> world = BlockLocations.resolveWorld(effect.location());
        if (world.isEmpty()) {
            return new RollbackResult.Skipped(effect, new RollbackReason.InvalidLocation(effect.location()));
        }
        Location location = new Location(world.get(),
                effect.location().x() + 0.5, effect.location().y(), effect.location().z() + 0.5);
        // Full-NBT resurrection when a snapshot exists. In practice it
        // rarely does: Paper's serializeEntity rejects dying entities,
        // so death records ship with null NBT (#29) — hence the
        // by-type fallback below rather than a skip.
        if (effect.serializedEntity() != null && !effect.serializedEntity().isBlank()) {
            try {
                byte[] bytes = Base64.getDecoder().decode(effect.serializedEntity());
                Entity entity = Bukkit.getUnsafe().deserializeEntity(bytes, world.get(), true, false);
                if (entity != null) {
                    entity.teleport(location);
                    RollbackEffect inverse = new RollbackEffect.EntityRemove(
                            effect.location(), effect.entityType(), entity.getUniqueId().toString());
                    return new RollbackResult.Applied(effect, inverse);
                }
            } catch (Throwable thrown) {
                // Version-brittle NBT (documented on EntityDeathRecord);
                // fall through to the by-type spawn.
            }
        }
        return spawnByType(effect, world.get(), location);
    }

    // Resurrection without a snapshot: a fresh entity of the recorded
    // type. Loses name/tame/equipment fidelity but beats refusing the
    // rollback outright — and matches what operators get elsewhere.
    private RollbackResult spawnByType(RollbackEffect.EntitySpawn effect, World world, Location location) {
        if (effect.entityType() == null || effect.entityType().isBlank()) {
            return new RollbackResult.Skipped(effect, new RollbackReason.NotSupported(
                    "No entity NBT and no entity type recorded."));
        }
        try {
            org.bukkit.NamespacedKey key = org.bukkit.NamespacedKey.minecraft(
                    effect.entityType().toLowerCase(java.util.Locale.ROOT));
            org.bukkit.entity.EntityType type =
                    org.bukkit.Registry.ENTITY_TYPE.get(key);
            if (type == null || !type.isSpawnable()) {
                return new RollbackResult.Skipped(effect, new RollbackReason.NotSupported(
                        "Entity type '" + effect.entityType() + "' is not spawnable."));
            }
            Entity entity = world.spawnEntity(location, type);
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

        // Force-overwrite (#69): restore the recorded block regardless of
        // the live state — no conflict guard, matching the fast path and the
        // original Spyglass / CoreProtect.
        Block block = world.get().getBlockAt(effect.location().x(), effect.location().y(), effect.location().z());
        applySnapshot(block, effect.replacement());
        RollbackEffect inverse = new RollbackEffect.BlockReplace(
                effect.location(), effect.replacement(), effect.expectedCurrent());
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
