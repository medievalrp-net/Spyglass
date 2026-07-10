package net.medievalrp.spyglass.plugin.rollback;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.BitSet;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.medievalrp.spyglass.plugin.command.service.ServiceSupport;
import net.kyori.adventure.text.Component;
import net.medievalrp.spyglass.api.event.BlockSnapshot;
import net.medievalrp.spyglass.api.event.StoredItem;
import net.medievalrp.spyglass.api.rollback.RollbackEffect;
import net.medievalrp.spyglass.api.rollback.RollbackEffectHandler;
import net.medievalrp.spyglass.api.rollback.RollbackReason;
import net.medievalrp.spyglass.api.rollback.RollbackResult;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.plugin.util.BlockLocations;
import net.medievalrp.spyglass.plugin.util.ChunkDirectWriter;
import net.medievalrp.spyglass.plugin.util.ChunkResender;
import net.medievalrp.spyglass.plugin.util.FluidTickScheduler;
import net.medievalrp.spyglass.plugin.util.ItemSerialization;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Banner;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.DecoratedPot;
import org.bukkit.block.Jukebox;
import org.bukkit.block.Sign;
import org.bukkit.block.banner.Pattern;
import org.bukkit.block.banner.PatternType;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.sign.Side;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class RollbackEngine {

    private static final Logger LOGGER =
            Logger.getLogger(RollbackEngine.class.getName());

    private Function<String, Optional<RollbackEffectHandler>> handlerLookup =
            type -> Optional.empty();
    private RollbackPhysicsBlocker physicsBlocker;

    // Per-tick budget for the apply loop, in ms. Sourced from
    // limits.rollback-tick-budget-ms. A larger budget eats most of the
    // tick and tanks TPS on big rollbacks; the default keeps the server
    // responsive at the cost of wall time.
    private volatile long tickBudgetMs = 15L;

    // Off-main executor for the per-block palette writes. When set,
    // the bulk setBlockState loop runs here while the main thread
    // only handles tile-entity state and the chunk-update packet.
    // PalettedContainer is per-section locked so concurrent reads
    // from the main thread stay consistent. Null = sync fallback.
    private volatile Executor worldWriteExecutor;

    // Salvage hook (#76): captures container inventories a rollback would
    // destroy, so they can be recovered via /sg inventory. Null when salvage
    // is disabled or in unit tests — the engine treats null as a no-op.
    private volatile SalvageHook salvageHook;

    public void setSalvageHook(SalvageHook hook) {
        this.salvageHook = hook;
    }

    // Marks the start of one rollback so salvaged containers are attributed to
    // the operator and the capturer's per-run dedup is reset. No-op without a
    // hook, so unit tests and salvage-disabled backends are unaffected.
    public void salvageBegin(String operatorName, java.util.UUID rollbackId) {
        SalvageHook hook = salvageHook;
        if (hook != null) {
            hook.begin(operatorName, rollbackId);
        }
    }

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

    // #208: max chunks that may be BLOCK-loaded (a getChunk on a not-yet-loaded
    // chunk) per resolve hop. A rollback over an unloaded region paces its loads
    // across ticks instead of bursting up to worldWriteBatchChunks blocking loads
    // into one tick. Already-loaded chunks don't count against it, so the common
    // in-view rollback is unaffected.
    private static final int MAX_CHUNK_LOADS_PER_HOP = 4;

    // Apply-phase breakdown (reset per applyAllChunked, logged on
    // completion). writeNanos = wall-time the pool spends on palette
    // writes (sum of per-batch barrier waits); postNanos = main-thread
    // tile-entity/finish/resend; resolveNanos = main-thread getChunk +
    // prepareChunk; batches = barrier count; the hop latency (idle ticks
    // between batches) is total - write - post - resolve.
    private final AtomicLong applyResolveNanos =
            new AtomicLong();
    private final AtomicLong applyWriteNanos =
            new AtomicLong();
    private final AtomicLong applyPostNanos =
            new AtomicLong();
    private final AtomicInteger applyBatchCount =
            new AtomicInteger();
    private final AtomicInteger applyChunkApplies =
            new AtomicInteger();

    // Parsed BlockData is shared across rollbacks. createBlockData is
    // comparatively expensive and BlockData is immutable, so caching one
    // instance per unique spec string is a big win on the hot loop.
    private static final ConcurrentHashMap<String, BlockData> BLOCK_DATA_CACHE =
            new ConcurrentHashMap<>();

    private static BlockData blockDataFor(String spec) {
        BlockData cached = BLOCK_DATA_CACHE.get(spec);
        if (cached != null) {
            return cached;
        }
        BlockData parsed = Bukkit.createBlockData(spec);
        BlockData prior = BLOCK_DATA_CACHE.putIfAbsent(spec, parsed);
        return prior == null ? parsed : prior;
    }

    // THE block force-overwrite primitive. Every block-apply path writes
    // through here: restore the recorded block regardless of the live state,
    // with no conflict guard — matching the original Spyglass
    // BlockEntry.rollback() and CoreProtect, and the contract pinned by
    // RollbackEngineChaosTest. A grief rollback must put the block back even
    // where unlogged drift (water/lava/fire/falling blocks) moved into the
    // gap after the edit; cross-actor cases are handled by scoping the
    // rollback, not by a per-cell live-state guard. Centralizing the write is
    // deliberate: the expected-state guard that broke grief recovery had crept
    // into only the parallel/columnar paths. With one write site that contract
    // can no longer diverge per-path — any future guard belongs here, on every
    // path, or nowhere.
    private static void forceWriteCell(ChunkDirectWriter.ChunkContext ctx, World world,
                                       int x, int y, int z, BlockData bd) {
        if (ctx != null) {
            ctx.writeBlock(x, y, z, bd);
        } else {
            ChunkDirectWriter.writeBlock(world, x, y, z, bd);
        }
    }

    // After a chunk's palette writes land, drop every block entity whose
    // type no longer matches the palette - the ghosts a direct section
    // write leaves behind (#289): they resurrect containers when the
    // container phase resolves through them, keep looted inventories
    // alive for open GUIs (#299), persist to disk, and throw "Failed to
    // create block entity" on every later chunk load. Views on destroyed
    // containers are closed first so nobody keeps a window into a dead
    // inventory. Main thread only.
    private void pruneStaleBlockEntities(World world, int cx, int cz) {
        for (ChunkDirectWriter.StaleBlockEntity stale
                : ChunkDirectWriter.pruneStaleBlockEntities(world, cx, cz)) {
            if (stale.container()) {
                closeViewsAt(world, stale.x(), stale.y(), stale.z());
            }
        }
    }

    private static void closeViewsAt(World world, int x, int y, int z) {
        for (Player player : world.getPlayers()) {
            try {
                if (holderAt(player.getOpenInventory().getTopInventory().getHolder(), x, y, z)) {
                    player.closeInventory();
                }
            } catch (RuntimeException ignored) {
                // Best-effort: a missed close only leaves a stale window
                // that dies on the next inventory tick.
            }
        }
    }

    private static boolean holderAt(Object holder, int x, int y, int z) {
        if (holder instanceof org.bukkit.block.DoubleChest doubleChest) {
            return holderAt(doubleChest.getLeftSide(), x, y, z)
                    || holderAt(doubleChest.getRightSide(), x, y, z);
        }
        if (holder instanceof org.bukkit.inventory.BlockInventoryHolder blockHolder) {
            Block block = blockHolder.getBlock();
            return block.getX() == x && block.getY() == y && block.getZ() == z;
        }
        return false;
    }

    // The Applied result + its inverse for a restored block, built identically
    // by every object-path apply (sync, parallel worker, main post-pass, slow
    // path). The inverse swaps replacement<->expectedCurrent so /undo reverses
    // the write.
    private static RollbackResult.Applied appliedWithInverse(RollbackEffect.BlockReplace effect) {
        RollbackEffect inverse = new RollbackEffect.BlockReplace(
                effect.location(), effect.replacement(), effect.expectedCurrent());
        return new RollbackResult.Applied(effect, inverse);
    }

    public RollbackEngine() {
    }

    public void setCustomEffectLookup(Function<String, Optional<RollbackEffectHandler>> lookup) {
        this.handlerLookup = lookup == null
                ? type -> Optional.empty()
                : lookup;
    }

    public void setPhysicsBlocker(RollbackPhysicsBlocker blocker) {
        this.physicsBlocker = blocker;
    }

    public void setTickBudgetMs(long ms) {
        this.tickBudgetMs = Math.max(1L, ms);
    }

    public void setWorldWriteExecutor(Executor exec) {
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
    private volatile Plugin chunkTicketHolder;

    public void setChunkTicketHolder(Plugin plugin) {
        this.chunkTicketHolder = plugin;
    }

    /** Skip message for cells the live-state guard left untouched (#264). */
    public static final String PROTECTED_SKIP = "Live block is excluded by the query";

    /** Skip message for container work withheld by default (#287). */
    public static final String CONTAINERS_SKIP =
            "Containers are skipped by default (pass --containers to include them)";

    /** Skip message for entity work withheld by default (#287). */
    public static final String ENTITIES_SKIP =
            "Entities are skipped by default (pass --entities to include them)";

    // Materials the operator explicitly excluded (block:!x), per job. A cell
    // whose LIVE block is one of these is never overwritten: excluding the
    // chest's record used to leave older same-coordinate history free to
    // force-write the live chest back to the window's start state (#264).
    // This is deliberately NOT the general expected-state guard that #69
    // removed - that one skipped cells where unlogged drift (water/lava/
    // fire) moved in after the grief, which broke grief recovery. Guarding
    // only the operator's own exclusions cannot break that: unlogged drift
    // is never in the set, and an operator who names a material has said
    // "leave that material alone" in so many words. Do not widen this into
    // a general live-state comparison; the reasoning above is why the last
    // one was deleted.
    //
    // Per-job engine state, same contract as the apply-phase counters
    // above: jobs are queue-serialized, set at the public entry points.
    private volatile Set<String> protectedMaterials = Set.of();

    // #287: containers and entities are opt-in per job. Default TRUE so
    // direct engine callers (tests, the legacy undo replay, the api) keep
    // their behavior; RollbackService sets both from the query flags.
    private volatile boolean includeContainers = true;
    private volatile boolean includeEntities = true;

    // Materials whose block state is a Bukkit Container - the same set
    // SalvageCapturer keys on. Resolved once from the live registry; on any
    // failure (unit tests without a server) the set stays empty and the
    // container-block gate is inert, which the flag-independent gates on
    // the container-slot and entity paths do not depend on.
    private static volatile Set<Material> containerMaterials;

    private static Set<Material> containerMaterials() {
        Set<Material> resolved = containerMaterials;
        if (resolved != null) {
            return resolved;
        }
        Set<Material> out = new java.util.HashSet<>();
        try {
            for (Material material : Material.values()) {
                try {
                    if (material.isBlock() && !material.isLegacy()
                            && material.createBlockData().createBlockState()
                                    instanceof org.bukkit.block.Container) {
                        out.add(material);
                    }
                } catch (Throwable ignored) {
                    // Material without a resolvable state; not a container.
                }
            }
        } catch (Throwable ignored) {
            out.clear();
        }
        containerMaterials = Set.copyOf(out);
        return containerMaterials;
    }

    /**
     * Material names of the {@link #containerMaterials()} set, for
     * consumers outside the engine that must mirror the #287 gate's
     * notion of "container" - today the rolled-* audit synthesis
     * (#302). Empty when no registry is available.
     */
    public static Set<String> containerMaterialNames() {
        Set<String> names = new java.util.HashSet<>();
        for (Material material : containerMaterials()) {
            names.add(material.name());
        }
        return Set.copyOf(names);
    }

    // Synchronous entry point for tests. Production uses applyAllChunked
    // directly so the apply loop can yield between ticks.
    public List<RollbackResult> applyAll(List<RollbackEffect> effects, CommandSender sender) {
        return applyAllChunked(effects, sender, ServiceSupport.synchronous(), Integer.MAX_VALUE).join();
    }

    /** Sets the operator's excluded materials for the next job (#264). */
    public void protectMaterials(Set<String> materials) {
        this.protectedMaterials = materials == null ? Set.of() : Set.copyOf(materials);
    }

    /** Sets what the next job may touch beyond plain blocks (#287). */
    public void includeInRollback(boolean containers, boolean entities) {
        this.includeContainers = containers;
        this.includeEntities = entities;
    }

    // (original dead entity id -> resurrected id) map an undo replay
    // carries so EntityRemove can find the fresh copy a rollback
    // spawned (#294). Set per job; the queue serializes jobs.
    private volatile java.util.Map<UUID, UUID> entityAliases = java.util.Map.of();

    public void entityAliases(java.util.Map<UUID, UUID> aliases) {
        this.entityAliases = aliases == null ? java.util.Map.of() : java.util.Map.copyOf(aliases);
    }

    private boolean isProtectedLive(World world, int x, int y, int z) {
        return !protectedMaterials.isEmpty()
                && protectedMaterials.contains(world.getBlockAt(x, y, z).getType().name());
    }

    /**
     * Skip reason for a block cell, or null to write it. Two gates (#264,
     * #287): the operator's excluded materials, and - unless --containers -
     * any cell that would place a container or overwrite a live one. Main
     * thread only (live block reads).
     */
    private String blockCellSkip(World world, int x, int y, int z, Material replacement) {
        if (isProtectedLive(world, x, y, z)) {
            return PROTECTED_SKIP;
        }
        if (!includeContainers) {
            Set<Material> containers = containerMaterials();
            if ((replacement != null && containers.contains(replacement))
                    || containers.contains(world.getBlockAt(x, y, z).getType())) {
                return CONTAINERS_SKIP;
            }
        }
        return null;
    }

    // -- apply-phase failsafe (#127) ------------------------------
    // Every main-thread apply step that advances a rollback runs through
    // this wrapper. If the step throws, the throwable is routed to `done`
    // (the job future) instead of escaping into the scheduler and
    // vanishing. A vanished throwable leaves `done` forever uncompleted, so
    // RollbackService.streamPagesAndApply blocks on fut.join() and never
    // reaches jobQueue.finish() -- which wedges EVERY future rollback /
    // restore / undo job until the server restarts, and leaks the
    // physics-suppression region and pinned chunk tickets. Failing `done`
    // instead makes join() throw, the job is marked FAILED, the queue
    // advances, the operator is told, and the physics region is released by
    // the done.whenComplete hook.
    private static void guarded(CompletableFuture<?> done, Runnable step) {
        try {
            step.run();
        } catch (Throwable thrown) {
            if (!done.completeExceptionally(thrown)) {
                // `done` already settled (cancelled, or a prior failure won
                // the race); surface the secondary failure so it isn't silent.
                LOGGER.log(Level.SEVERE,
                        "Rollback apply step threw after the job future already completed", thrown);
            }
        }
    }

    public CompletableFuture<List<RollbackResult>> applyAllChunked(
            List<RollbackEffect> effects, CommandSender sender,
            ServiceSupport scheduler, int batchSize) {
        return applyAllChunked(effects, sender, scheduler, batchSize,
                new AtomicBoolean(false));
    }

    public CompletableFuture<List<RollbackResult>> applyAllChunked(
            List<RollbackEffect> effects, CommandSender sender,
            ServiceSupport scheduler, int batchSize,
            AtomicBoolean cancelFlag) {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("RollbackEngine.applyAllChunked must run on the main thread.");
        }
        CompletableFuture<List<RollbackResult>> done = new CompletableFuture<>();
        done.whenComplete((r, t) -> {
            protectedMaterials = Set.of();
            includeContainers = true;
            includeEntities = true;
        });
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

            scheduler.onMainThread(() -> guarded(done, () -> applyChunkByChunk(0, effects, resultArray,
                    blockReplaceIndices, blockReplaceEffects,
                    sender, scheduler, batchSize, done, cancelFlag)));
        };

        if (worldWriteExecutor != null) {
            CompletableFuture.runAsync(() -> guarded(done, stageOne), worldWriteExecutor);
        } else {
            guarded(done, stageOne);
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
                Arrays.sort(composite);
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

    /**
     * Whether the resolve loop should stop this hop before a not-yet-loaded
     * chunk, to pace synchronous chunk loads across ticks (#208). Never defers an
     * already-loaded chunk, and always resolves at least one chunk per hop (an
     * empty batch never defers) so progress is guaranteed even in a fully cold
     * region. Package-private for unit testing.
     */
    static boolean deferForChunkLoadPacing(boolean chunkLoaded, int loadsThisHop, boolean batchEmpty) {
        return !chunkLoaded && !batchEmpty && loadsThisHop >= MAX_CHUNK_LOADS_PER_HOP;
    }

    private static void sortByComparator(List<Integer> indices,
                                         List<RollbackEffect.BlockReplace> effects) {
        int n = indices.size();
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> {
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
                                   AtomicBoolean cancelFlag) {
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
                if (salvageHook != null) {
                    salvageHook.onChunkResolved(world, cx, cz);
                }

                for (int j = i; j < chunkEnd; j++) {
                    int targetIndex = blockReplaceIndices.get(j);
                    RollbackEffect.BlockReplace effect = blockReplaceEffects.get(j);
                    BlockSnapshot replacement = effect.replacement();
                    BlockLocation loc = effect.location();
                    try {
                        String cellSkip = blockCellSkip(world, loc.x(), loc.y(), loc.z(),
                                replacement.material());
                        if (cellSkip != null) {
                            resultArray[targetIndex] = new RollbackResult.Skipped(effect,
                                    new RollbackReason.NotSupported(cellSkip));
                            continue;
                        }
                        BlockData bd =
                                blockDataFor(replacement.blockData());
                        forceWriteCell(chunkCtx, world, loc.x(), loc.y(), loc.z(), bd);
                        // Palette writes skip the block-entity lifecycle;
                        // register the tile entity the new state needs so
                        // container/sign payloads land in a real one (#289).
                        if (ChunkDirectWriter.stateHasBlockEntity(bd)) {
                            ChunkDirectWriter.ensureBlockEntity(world, loc.x(), loc.y(), loc.z());
                        }
                        if (!replacement.simple()) {
                            Block block = world.getBlockAt(loc.x(), loc.y(), loc.z());
                            applyTileEntityState(block, replacement);
                        }
                        resultArray[targetIndex] = appliedWithInverse(effect);
                    } catch (RuntimeException thrown) {
                        resultArray[targetIndex] = new RollbackResult.Skipped(effect,
                                new RollbackReason.Error("Unhandled error: " + thrown.getMessage()));
                    }
                }
                pruneStaleBlockEntities(world, cx, cz);
                ChunkDirectWriter.finishChunk(chunkCtx);
                // Direct writes never schedule fluid ticks; re-arm the fluid
                // engine over this chunk's cells and their shell (#270).
                FluidTickScheduler.Pass fluids = FluidTickScheduler.begin(world);
                for (int j = i; j < chunkEnd; j++) {
                    BlockLocation loc = blockReplaceEffects.get(j).location();
                    fluids.touch(loc.x(), loc.y(), loc.z());
                }
                // Direct NMS writes skip the per-block packet queue,
                // so push the new chunk to viewers ourselves.
                try {
                    ChunkResender.resend(world, cx, cz);
                } catch (RuntimeException ignored) {
                    // Best-effort: a failed resend only delays the client's view
                    // of the restored chunk until its next natural update.
                }
                if (salvageHook != null) {
                    salvageHook.onChunkWritten(world, cx, cz);
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
            scheduler.onMainThreadLater(1L, () -> guarded(done, () -> applyChunkByChunk(
                    next, effects, resultArray, blockReplaceIndices, blockReplaceEffects,
                    sender, scheduler, batchSize, done, cancelFlag)));
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
                                         AtomicBoolean cancelFlag) {
        int total = blockReplaceIndices.size();
        final Plugin ticketHolder = chunkTicketHolder;
        int parallelism = Math.max(1, worldWriteParallelism);

        // Resolve up to `parallelism` chunks. getChunk + prepareChunk +
        // addPluginChunkTicket all run here on the main thread; the
        // workers only touch the per-section palette afterwards.
        int maxBatchChunks = Math.max(parallelism, worldWriteBatchChunks);
        long tResolve = System.nanoTime();
        List<ChunkWork> batch = new ArrayList<>(Math.min(maxBatchChunks, 64));
        int cursor = from;
        int loadsThisHop = 0;
        try {
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

            // #208: pace synchronous chunk loads across ticks. prepareChunk's
            // getChunk blocks to load an unloaded chunk; without this a rollback
            // over an unloaded region would trigger up to maxBatchChunks blocking
            // loads in a single tick and stall it. Already-loaded chunks (rolling
            // back where a player stands - the common case) never hit the cap and
            // resolve at full speed; a cold region just spreads its loads over
            // more ticks, trading wall-time for TPS like the apply budget does.
            boolean chunkLoaded = world.isChunkLoaded(cx, cz);
            if (deferForChunkLoadPacing(chunkLoaded, loadsThisHop, batch.isEmpty())) {
                break;
            }

            // Pin the chunk loaded, then resolve its section context —
            // both on main, so the worker never calls into the chunk
            // system. addPluginChunkTicket is thread-safe per Paper.
            boolean ticketAdded = false;
            if (ticketHolder != null) {
                try {
                    ticketAdded = world.addPluginChunkTicket(cx, cz, ticketHolder);
                } catch (Throwable t) {
                    // Ticket add failed: the chunk isn't pinned, so it can
                    // unload mid-write and silently drop cells. Rare; log at
                    // FINE so it's diagnosable, then proceed unpinned.
                    LOGGER.log(Level.FINE, "Rollback chunk-ticket add failed for "
                            + cx + "," + cz, t);
                }
            }
            try {
                ChunkDirectWriter.ChunkContext ctx =
                        ChunkDirectWriter.prepareChunk(world, cx, cz);
                if (salvageHook != null) {
                    salvageHook.onChunkResolved(world, cx, cz);
                }
                // Live-state reads are main-thread only; the worker half
                // consults the precomputed reasons instead of the world
                // (#264 exclusions, #287 container gate).
                String[] cellSkips = null;
                if (!protectedMaterials.isEmpty() || !includeContainers) {
                    cellSkips = new String[chunkEnd - cursor];
                    for (int j = cursor; j < chunkEnd; j++) {
                        RollbackEffect.BlockReplace eff = blockReplaceEffects.get(j);
                        BlockLocation loc = eff.location();
                        cellSkips[j - cursor] = blockCellSkip(world, loc.x(), loc.y(), loc.z(),
                                eff.replacement() == null ? null : eff.replacement().material());
                    }
                }
                batch.add(new ChunkWork(world, cx, cz, cursor, chunkEnd, ctx, ticketAdded,
                        cellSkips));
                if (!chunkLoaded) {
                    loadsThisHop++;
                }
            } catch (Throwable t) {
                // prepareChunk / onChunkResolved threw after this chunk was
                // pinned: release its ticket here (it never reaches the post
                // phase that would), then rethrow so the guarded caller fails
                // the job. The already-resolved chunks are freed below (#127).
                if (ticketAdded && ticketHolder != null) {
                    try {
                        world.removePluginChunkTicket(cx, cz, ticketHolder);
                    } catch (Throwable ignored) {
                        // best-effort
                    }
                }
                throw t;
            }
            cursor = chunkEnd;
            }
        } catch (Throwable t) {
            // Resolve phase failed mid-batch: the chunks already pinned will
            // never reach applyChunkPostMain (which releases their tickets),
            // so free them here before failing the job (#127).
            for (ChunkWork w : batch) {
                if (w.ticketAdded && ticketHolder != null) {
                    try {
                        w.world.removePluginChunkTicket(w.cx, w.cz, ticketHolder);
                    } catch (Throwable ignored) {
                        // best-effort
                    }
                }
            }
            throw t;
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
            futures[b] = CompletableFuture.runAsync(
                    () -> writeChunkPalettes(work, resultArray, blockReplaceIndices, blockReplaceEffects),
                    worldWriteExecutor);
        }

        CompletableFuture.allOf(futures)
                .whenComplete((v, throwable) -> {
                    applyWriteNanos.addAndGet(System.nanoTime() - tDispatch);
                    applyBatchCount.incrementAndGet();
                    applyChunkApplies.addAndGet(batchChunks);
                    if (throwable != null) {
                        // A palette worker threw (parse failures are marked
                        // Skipped on the worker, not thrown). Surface it instead
                        // of swallowing; we still run the post phase below so
                        // every pinned chunk's ticket is released (#127).
                        LOGGER.log(Level.WARNING, "Rollback chunk palette write batch failed", throwable);
                    }
                    scheduler.onMainThread(() -> guarded(done, () -> {
                    // Main-thread post for every chunk in the batch: the
                    // heavy palette writes already happened off-main, so
                    // this is just tile-entity state, finishChunk, the
                    // chunk packet, and the ticket release.
                    long tPost = System.nanoTime();
                    Throwable postFailure = null;
                    for (ChunkWork work : batch) {
                        try {
                            applyChunkPostMain(work, resultArray, blockReplaceIndices, blockReplaceEffects);
                        } catch (Throwable t) {
                            // applyChunkPostMain's own finally already released
                            // this chunk's ticket; keep going so the rest of the
                            // batch is cleaned up too, then fail the job (#127).
                            if (postFailure == null) {
                                postFailure = t;
                            }
                        }
                    }
                    applyPostNanos.addAndGet(System.nanoTime() - tPost);
                    if (postFailure != null) {
                        done.completeExceptionally(postFailure);
                        return;
                    }
                    if (sender instanceof Player p && total > 0) {
                        p.sendActionBar(Component.text("Rolling back " + batchEnd + " / " + total));
                    }
                    if (batchEnd < total) {
                        applyChunkByChunk(batchEnd, effects, resultArray, blockReplaceIndices,
                                blockReplaceEffects, sender, scheduler, batchSize, done, cancelFlag);
                    } else {
                        runContainerAndLeftover(effects, resultArray, sender, scheduler, batchSize, done);
                    }
                    }));
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
        BlockData[] writes =
                new BlockData[chunkSize];
        BitSet nonSimpleMask = new BitSet(chunkSize);
        for (int j = 0; j < chunkSize; j++) {
            int targetIndex = blockReplaceIndices.get(from + j);
            RollbackEffect.BlockReplace effect = blockReplaceEffects.get(from + j);
            if (work.cellSkips != null && work.cellSkips[j] != null) {
                resultArray[targetIndex] = new RollbackResult.Skipped(effect,
                        new RollbackReason.NotSupported(work.cellSkips[j]));
                continue;
            }
            BlockData bd;
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
            forceWriteCell(work.ctx, work.world, loc.x(), loc.y(), loc.z(), bd);
            if (replacement.simple() && !ChunkDirectWriter.stateHasBlockEntity(bd)) {
                // No tile entity to apply or register; the palette write
                // is the whole apply. Build Applied here on the worker.
                resultArray[targetIndex] = appliedWithInverse(effect);
            } else {
                // Needs the main thread: BlockState.update for the tile
                // payload, and/or block-entity registration for a tile
                // block the palette write placed (#289).
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
        BitSet nonSimpleMask = work.nonSimpleMask;
        BlockData[] writes = work.writes;
        try {
            // The worker's palette writes are all in; drop the block
            // entities they orphaned before any tile state resolves
            // through a stale one (#289, #299).
            pruneStaleBlockEntities(world, work.cx, work.cz);
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
                            // the recorded block here on main.
                            forceWriteCell(null, world, loc.x(), loc.y(), loc.z(), writes[j]);
                        }
                        // Register the block entity the new tile state
                        // needs before applying its payload (#289); a
                        // detached state write otherwise vanishes (#298).
                        if (work.ctx != null && ChunkDirectWriter.stateHasBlockEntity(writes[j])) {
                            ChunkDirectWriter.ensureBlockEntity(
                                    world, loc.x(), loc.y(), loc.z());
                        }
                        if (!replacement.simple()) {
                            Block block = world.getBlockAt(loc.x(), loc.y(), loc.z());
                            applyTileEntityState(block, replacement);
                        }
                        resultArray[targetIndex] = appliedWithInverse(effect);
                    } catch (RuntimeException ex) {
                        resultArray[targetIndex] = new RollbackResult.Skipped(effect,
                                new RollbackReason.Error("Unhandled error: " + ex.getMessage()));
                    }
                }
            }
            ChunkDirectWriter.finishChunk(work.ctx);
            // Direct writes never schedule fluid ticks; re-arm the fluid
            // engine over this chunk's cells and their shell (#270).
            if (writes != null) {
                FluidTickScheduler.Pass fluids = FluidTickScheduler.begin(world);
                for (int j = 0; j < writes.length; j++) {
                    BlockLocation loc = blockReplaceEffects.get(from + j).location();
                    fluids.touch(loc.x(), loc.y(), loc.z());
                }
            }
            // Direct NMS writes skip the per-block packet queue, so push
            // the new chunk to viewers ourselves.
            try {
                ChunkResender.resend(world, work.cx, work.cz);
            } catch (RuntimeException ignored) {
                // Best-effort: a failed resend only delays the client's view
                // of the restored chunk until its next natural update.
            }
            if (salvageHook != null) {
                salvageHook.onChunkWritten(world, work.cx, work.cz);
            }
        } finally {
            if (work.ticketAdded && chunkTicketHolder != null) {
                try {
                    world.removePluginChunkTicket(work.cx, work.cz, chunkTicketHolder);
                } catch (Throwable ignored) {
                    // Best-effort: a leaked ticket is reclaimed on plugin
                    // disable; not worth surfacing.
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
        final String[] cellSkips;
        volatile BlockData[] writes;
        volatile BitSet nonSimpleMask;

        ChunkWork(World world, int cx, int cz, int from, int chunkEnd,
                  ChunkDirectWriter.ChunkContext ctx, boolean ticketAdded,
                  String[] cellSkips) {
            this.world = world;
            this.cx = cx;
            this.cz = cz;
            this.from = from;
            this.chunkEnd = chunkEnd;
            this.ctx = ctx;
            this.ticketAdded = ticketAdded;
            this.cellSkips = cellSkips;
        }
    }

    // ----- Columnar apply -----------------------------------------
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
        /** Cells the live-state guard left untouched (#264). Benign. */
        public long protectedCells;
        /** Live-container cells withheld without --containers (#287). Benign. */
        public long containerCells;

        /** Total skipped across every benign and error reason. */
        public long skipped() {
            return blockChanged + unparseable + invalidLocation + cancelled
                    + protectedCells + containerCells;
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
            protectedCells += other.protectedCells;
            containerCells += other.containerCells;
        }
    }

    public CompletableFuture<ApplyCounts> applyColumnsChunked(
            UUID worldId, BlockColumns cols, CommandSender sender,
            ServiceSupport scheduler, int batchSize,
            AtomicBoolean cancelFlag) {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("RollbackEngine.applyColumnsChunked must run on the main thread.");
        }
        CompletableFuture<ApplyCounts> done = new CompletableFuture<>();
        done.whenComplete((r, t) -> {
            protectedMaterials = Set.of();
            includeContainers = true;
            includeEntities = true;
        });
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
            scheduler.onMainThread(() -> guarded(done, () -> applyColumnBatch(
                    world, cols, order, 0, counts, sender, scheduler, batchSize, done, cancelFlag)));
        };
        if (worldWriteExecutor != null) {
            CompletableFuture.runAsync(() -> guarded(done, stageOne), worldWriteExecutor);
        } else {
            guarded(done, stageOne);
        }
        return done;
    }

    private void applyColumnBatch(World world, BlockColumns cols, int[] order, int from,
                                  ApplyCounts counts, CommandSender sender, ServiceSupport scheduler,
                                  int batchSize, CompletableFuture<ApplyCounts> done,
                                  AtomicBoolean cancelFlag) {
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
        final Plugin ticketHolder = chunkTicketHolder;
        int parallelism = Math.max(1, worldWriteParallelism);
        int maxBatchChunks = Math.max(parallelism, worldWriteBatchChunks);
        // Resolve up to maxBatchChunks chunks on the main thread: contiguous
        // runs of the sorted order share a chunk, so one walk groups them.
        List<ColChunk> batch = new ArrayList<>(Math.min(maxBatchChunks, 64));
        int cursor = from;
        int loadsThisHop = 0;
        try {
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
            // #208: pace synchronous chunk loads across ticks (see the mirror in
            // applyChunkBatchParallel). Already-loaded chunks resolve at full
            // speed; a cold region spreads its blocking getChunk loads over hops.
            boolean chunkLoaded = world.isChunkLoaded(cx, cz);
            if (deferForChunkLoadPacing(chunkLoaded, loadsThisHop, batch.isEmpty())) {
                break;
            }
            boolean ticketAdded = false;
            if (ticketHolder != null) {
                try {
                    ticketAdded = world.addPluginChunkTicket(cx, cz, ticketHolder);
                } catch (Throwable t) {
                    // Ticket add failed: the chunk isn't pinned, so it can
                    // unload mid-write and silently drop cells. Rare; log at
                    // FINE so it's diagnosable, then proceed unpinned.
                    LOGGER.log(Level.FINE, "Rollback chunk-ticket add failed for "
                            + cx + "," + cz, t);
                }
            }
            try {
                ChunkDirectWriter.ChunkContext ctx = ChunkDirectWriter.prepareChunk(world, cx, cz);
                if (salvageHook != null) {
                    salvageHook.onChunkResolved(world, cx, cz);
                }
                // Live-state reads are main-thread only; the worker half
                // consults the mask instead of the world (#264 exclusions,
                // #287 container gate). The replacement side is parsed out
                // of the lean stream's blockdata string - an EMPTY container
                // is a simple snapshot and rides this path, so trusting
                // "columnar means no container" let plain rollbacks place
                // container blocks (#290). Counting happens at classify
                // time so the worker only skips.
                BitSet skipMask = null;
                long protectedHere = 0;
                long containersHere = 0;
                if (!protectedMaterials.isEmpty() || !includeContainers) {
                    java.util.HashMap<String, Material> materialCache = new java.util.HashMap<>();
                    skipMask = new BitSet(rangeEnd - cursor);
                    for (int k = cursor; k < rangeEnd; k++) {
                        int cellIdx = order[k];
                        String reason = blockCellSkip(world,
                                cols.x(cellIdx), cols.y(cellIdx), cols.z(cellIdx),
                                replacementMaterial(cols.replData(cellIdx), materialCache));
                        if (reason != null) {
                            skipMask.set(k - cursor);
                            if (PROTECTED_SKIP.equals(reason)) {
                                protectedHere++;
                            } else {
                                containersHere++;
                            }
                        }
                    }
                }
                ColChunk colChunk = new ColChunk(cx, cz, cursor, rangeEnd, ctx, ticketAdded, skipMask);
                colChunk.protectedCells = protectedHere;
                colChunk.containerCells = containersHere;
                batch.add(colChunk);
                if (!chunkLoaded) {
                    loadsThisHop++;
                }
            } catch (Throwable t) {
                // Release this chunk's just-added ticket before failing the
                // batch; the rest are freed below (#127).
                if (ticketAdded && ticketHolder != null) {
                    try {
                        world.removePluginChunkTicket(cx, cz, ticketHolder);
                    } catch (Throwable ignored) {
                        // best-effort
                    }
                }
                throw t;
            }
            cursor = rangeEnd;
            }
        } catch (Throwable t) {
            // Resolve phase failed: free the tickets pinned for chunks already
            // in the batch (their post phase never runs to release them), then
            // rethrow so the guarded caller fails the job (#127).
            for (ColChunk cc : batch) {
                if (cc.ticketAdded && ticketHolder != null) {
                    try {
                        world.removePluginChunkTicket(cc.cx, cc.cz, ticketHolder);
                    } catch (Throwable ignored) {
                        // best-effort
                    }
                }
            }
            throw t;
        }
        final int batchEnd = cursor;

        // Main-thread post: finish/resend each chunk, release tickets, sum
        // the per-chunk counts, then advance. The heavy palette writes
        // already happened off-main (or inline when no executor is wired).
        Runnable afterWrites = () -> scheduler.onMainThread(() -> guarded(done, () -> {
            Throwable postFailure = null;
            for (ColChunk cc : batch) {
                try {
                    if (cc.ctx == null) {
                        // prepareChunk failed: write this chunk's cells on the
                        // main thread via the single-block fallback.
                        writeColumnChunkMain(world, cols, order, cc);
                    } else {
                        // Palette writes skip the block-entity lifecycle:
                        // drop the entities they orphaned (#289, #299) and
                        // register the ones the written tile states need.
                        pruneStaleBlockEntities(world, cc.cx, cc.cz);
                        if (cc.tileCells != null) {
                            for (int[] cell : cc.tileCells) {
                                ChunkDirectWriter.ensureBlockEntity(
                                        world, cell[0], cell[1], cell[2]);
                            }
                        }
                        ChunkDirectWriter.finishChunk(cc.ctx);
                    }
                    // Direct writes never schedule fluid ticks; re-arm the
                    // fluid engine over this chunk's cells and shell (#270).
                    FluidTickScheduler.Pass fluids = FluidTickScheduler.begin(world);
                    for (int k = cc.from; k < cc.rangeEnd; k++) {
                        int cellIdx = order[k];
                        fluids.touch(cols.x(cellIdx), cols.y(cellIdx), cols.z(cellIdx));
                    }
                    try {
                        ChunkResender.resend(world, cc.cx, cc.cz);
                    } catch (RuntimeException ignored) {
                        // Best-effort: a failed resend only delays the client's view
                        // of the restored chunk until its next natural update.
                    }
                    if (salvageHook != null) {
                        salvageHook.onChunkWritten(world, cc.cx, cc.cz);
                    }
                    counts.applied += cc.applied;
                    counts.blockChanged += cc.blockChanged;
                    counts.unparseable += cc.unparseable;
                    counts.protectedCells += cc.protectedCells;
                    counts.containerCells += cc.containerCells;
                } catch (Throwable t) {
                    // finishChunk / onChunkWritten threw; record the failure and
                    // keep cleaning up the rest of the batch (#127).
                    if (postFailure == null) {
                        postFailure = t;
                    }
                } finally {
                    // Always release the pinned chunk, even if the post step
                    // above threw -- otherwise a finishChunk failure leaks the
                    // ticket for the life of the world (#127).
                    if (cc.ticketAdded && ticketHolder != null) {
                        try {
                            world.removePluginChunkTicket(cc.cx, cc.cz, ticketHolder);
                        } catch (Throwable ignored) {
                            // Best-effort: a leaked ticket is reclaimed on plugin
                            // disable; not worth surfacing.
                        }
                    }
                }
            }
            if (postFailure != null) {
                done.completeExceptionally(postFailure);
                return;
            }
            if (sender instanceof Player p) {
                p.sendActionBar(Component.text("Rolling back " + batchEnd + " / " + total));
            }
            applyColumnBatch(world, cols, order, batchEnd, counts, sender, scheduler, batchSize, done, cancelFlag);
        }));

        if (worldWriteExecutor != null) {
            @SuppressWarnings("unchecked")
            CompletableFuture<Void>[] futures = new CompletableFuture[batch.size()];
            for (int b = 0; b < batch.size(); b++) {
                ColChunk cc = batch.get(b);
                futures[b] = CompletableFuture.runAsync(
                        () -> writeColumnChunk(cols, order, cc), worldWriteExecutor);
            }
            CompletableFuture.allOf(futures)
                    .whenComplete((v, t) -> {
                        if (t != null) {
                            // A column worker threw; surface it, then still run
                            // afterWrites so tickets are released and the job
                            // can fail cleanly rather than wedge (#127).
                            LOGGER.log(Level.WARNING, "Rollback column palette write batch failed", t);
                        }
                        afterWrites.run();
                    });
        } else {
            Throwable writeFailure = null;
            for (ColChunk cc : batch) {
                try {
                    writeColumnChunk(cols, order, cc);
                } catch (Throwable t) {
                    if (writeFailure == null) {
                        writeFailure = t;
                    }
                }
            }
            if (writeFailure != null) {
                LOGGER.log(Level.WARNING, "Rollback column palette write failed", writeFailure);
            }
            afterWrites.run();
        }
    }

    // Worker-thread half: parse + write one chunk's cells from the columns.
    // No chunk-system access (prepareChunk already ran on main) — only the
    // locked LevelChunkSection write. Counts land on the work unit.
    // Material of a lean-stream blockdata string, e.g.
    // "minecraft:chest[facing=west]" -> CHEST, so the columnar gate can
    // see what it is about to place (#290). Null-cached per distinct
    // string; the palette is tiny so this is a handful of parses per
    // batch.
    private static @org.jetbrains.annotations.Nullable Material replacementMaterial(
            String blockData, java.util.Map<String, Material> cache) {
        if (blockData == null) {
            return null;
        }
        Material cached = cache.get(blockData);
        if (cached == null && !cache.containsKey(blockData)) {
            int bracket = blockData.indexOf('[');
            cached = Material.matchMaterial(
                    (bracket >= 0 ? blockData.substring(0, bracket) : blockData).trim());
            cache.put(blockData, cached);
        }
        return cached;
    }

    private void writeColumnChunk(BlockColumns cols, int[] order, ColChunk cc) {
        if (cc.ctx == null) {
            return; // handled on the main thread in writeColumnChunkMain
        }
        long applied = 0;
        long changed = 0;
        long unparse = 0;
        for (int k = cc.from; k < cc.rangeEnd; k++) {
            if (cc.skipMask != null && cc.skipMask.get(k - cc.from)) {
                continue; // counted at classify time on the main thread
            }
            int idx = order[k];
            int x = cols.x(idx);
            int y = cols.y(idx);
            int z = cols.z(idx);
            BlockData bd;
            try {
                bd = blockDataFor(cols.replData(idx));
            } catch (RuntimeException thrown) {
                bd = null;
            }
            if (bd == null) {
                unparse++;
                continue;
            }
            forceWriteCell(cc.ctx, null, x, y, z, bd);
            // Rare on this path (post-#290 containers ride the object
            // path), but blank signs, skulls and the like are simple AND
            // tile - remember them for main-thread registration (#289).
            if (ChunkDirectWriter.stateHasBlockEntity(bd)) {
                if (cc.tileCells == null) {
                    cc.tileCells = new java.util.ArrayList<>(4);
                }
                cc.tileCells.add(new int[]{x, y, z});
            }
            applied++;
        }
        cc.applied = applied;
        cc.blockChanged = changed;
        cc.unparseable = unparse;
    }

    // Main-thread fallback for a chunk whose prepareChunk failed: single
    // block writes, force-overwrite (no live-state guard).
    private void writeColumnChunkMain(World world, BlockColumns cols, int[] order, ColChunk cc) {
        long applied = 0;
        long unparse = 0;
        for (int k = cc.from; k < cc.rangeEnd; k++) {
            if (cc.skipMask != null && cc.skipMask.get(k - cc.from)) {
                continue; // counted at classify time on the main thread
            }
            int idx = order[k];
            int x = cols.x(idx);
            int y = cols.y(idx);
            int z = cols.z(idx);
            BlockData bd;
            try {
                bd = blockDataFor(cols.replData(idx));
            } catch (RuntimeException thrown) {
                bd = null;
            }
            if (bd == null) {
                unparse++;
                continue;
            }
            forceWriteCell(null, world, x, y, z, bd);
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
        final BitSet skipMask;
        volatile long applied;
        volatile long blockChanged;
        volatile long unparseable;
        volatile long protectedCells;
        volatile long containerCells;
        // Cells whose written state carries a block entity - the worker
        // collects them so the main-thread post pass can register the
        // entities a palette write skips (#289). Written by exactly one
        // worker, read after its future completes.
        java.util.List<int[]> tileCells;

        ColChunk(int cx, int cz, int from, int rangeEnd,
                 ChunkDirectWriter.ChunkContext ctx, boolean ticketAdded,
                 BitSet skipMask) {
            this.cx = cx;
            this.cz = cz;
            this.from = from;
            this.rangeEnd = rangeEnd;
            this.skipMask = skipMask;
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
            scheduler.onMainThreadLater(1L, () -> guarded(done, () -> applyLeftoverBatch(
                    next, effects, resultArray, sender, scheduler, batchSize, done)));
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
                BlockState live = block.getState(false);
                if (live instanceof Jukebox liveJukebox) {
                    ItemStack disc =
                            ItemSerialization.decode(snapshot.jukeboxRecord());
                    if (disc != null) {
                        liveJukebox.setRecord(disc);
                    }
                }
            } catch (Throwable jukeboxFailure) {
                // Disc lost; block stays a jukebox without its record.
            }
        }

        if (state instanceof DecoratedPot pot
                && !snapshot.potSherds().isEmpty()) {
            applyPotSherds(pot, snapshot.potSherds());
        }

        state.update(true, false);
    }

    // Order matches DecoratedPot.Side enum declaration; the snapshot
    // list is built the same way in BlockSnapshots.capture.
    private void applyPotSherds(DecoratedPot pot, List<String> names) {
        DecoratedPot.Side[] sides = DecoratedPot.Side.values();
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
        if (!includeContainers) {
            for (int i = 0; i < indices.size(); i++) {
                resultArray[indices.get(i)] = new RollbackResult.Skipped(writes.get(i),
                        new RollbackReason.NotSupported(CONTAINERS_SKIP));
            }
            return;
        }
        Optional<World> world = BlockLocations.resolveWorld(location);
        if (world.isEmpty()) {
            for (int i = 0; i < indices.size(); i++) {
                resultArray[indices.get(i)] = new RollbackResult.Skipped(writes.get(i),
                        new RollbackReason.InvalidLocation(location));
            }
            return;
        }
        String containerType = null;
        for (RollbackEffect.ContainerSlotWrite csw : writes) {
            if (csw.containerType() != null) {
                containerType = csw.containerType();
                break;
            }
        }
        SurfaceOrSkip resolved = resolveContainerSurface(world.get(), location, containerType);
        if (resolved.surface() == null) {
            for (int i = 0; i < indices.size(); i++) {
                resultArray[indices.get(i)] = new RollbackResult.Skipped(writes.get(i),
                        new RollbackReason.NotSupported(resolved.skipReason()));
            }
            return;
        }

        SlotSurface surface = resolved.surface();
        int size = surface.size();
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
            ItemStack current = surface.get(slot);
            if (!matches(csw.expectedCurrent(), slot, current)) {
                resultArray[indices.get(i)] = new RollbackResult.Skipped(csw,
                        new RollbackReason.Guarded("Container slot changed."));
                continue;
            }
            StoredItem replacement = csw.replacement();
            surface.set(slot, replacement == null ? null : ItemSerialization.decode(replacement.data()));
            StoredItem inverseCurrent = ItemSerialization.storedItem(slot, current);
            RollbackEffect inverse = new RollbackEffect.ContainerSlotWrite(
                    location, slot, replacement, inverseCurrent, csw.containerType());
            resultArray[indices.get(i)] = new RollbackResult.Applied(csw, inverse);
            anyApplied = true;
        }
        if (anyApplied) {
            surface.flush();
        }
    }

    // ---- container slot surfaces (#293, #300) --------------------------

    /**
     * One writable container-slot surface. Widens the old
     * instanceof-Container check, which rejected inventory-holding tile
     * states that are not Containers (decorated pots, chiseled
     * bookshelves - #300) and could never see entity-held inventories
     * (storage/hopper minecarts, item frames - #293).
     */
    private interface SlotSurface {
        int size();

        ItemStack get(int slot);

        void set(int slot, ItemStack stack);

        /** Push writes for snapshot-backed surfaces; no-op for live ones. */
        default void flush() {
        }
    }

    private record SurfaceOrSkip(SlotSurface surface, String skipReason) {
    }

    private SurfaceOrSkip resolveContainerSurface(World world, BlockLocation location,
                                                  String containerType) {
        Block block = world.getBlockAt(location.x(), location.y(), location.z());
        BlockState state = block.getState();
        if (state instanceof Container container) {
            // LIVE inventory - writes go straight to the block entity. The
            // old snapshot-then-update pattern silently lost every write on
            // shulker boxes (#298).
            Inventory inventory = container.getInventory();
            return new SurfaceOrSkip(new SlotSurface() {
                @Override
                public int size() {
                    return inventory.getSize();
                }

                @Override
                public ItemStack get(int slot) {
                    return inventory.getItem(slot);
                }

                @Override
                public void set(int slot, ItemStack stack) {
                    inventory.setItem(slot, stack);
                }
            }, null);
        }
        if (state instanceof org.bukkit.inventory.BlockInventoryHolder holder) {
            // Decorated pot / chiseled bookshelf (#300). getInventory() on a
            // placed holder is LIVE, same as Container - and following it
            // with state.update(true) re-wrote the block entity from the
            // stale snapshot, resurrecting exactly what the write removed
            // (the shulker lesson from #298 again). Write live, no flush.
            Inventory inventory = holder.getInventory();
            return new SurfaceOrSkip(new SlotSurface() {
                @Override
                public int size() {
                    return inventory.getSize();
                }

                @Override
                public ItemStack get(int slot) {
                    return inventory.getItem(slot);
                }

                @Override
                public void set(int slot, ItemStack stack) {
                    inventory.setItem(slot, stack);
                }
            }, null);
        }
        EntityType entityType = entityContainerType(containerType);
        if (entityType != null) {
            Entity entity = nearestEntityOfType(world, location, entityType);
            if (entity == null) {
                return new SurfaceOrSkip(null, "Container entity (" + containerType
                        + ") is no longer near the recorded spot.");
            }
            if (entity instanceof org.bukkit.inventory.InventoryHolder holder) {
                Inventory inventory = holder.getInventory();
                return new SurfaceOrSkip(new SlotSurface() {
                    @Override
                    public int size() {
                        return inventory.getSize();
                    }

                    @Override
                    public ItemStack get(int slot) {
                        return inventory.getItem(slot);
                    }

                    @Override
                    public void set(int slot, ItemStack stack) {
                        inventory.setItem(slot, stack);
                    }
                }, null);
            }
            if (entity instanceof org.bukkit.entity.ItemFrame frame) {
                return new SurfaceOrSkip(new SlotSurface() {
                    @Override
                    public int size() {
                        return 1;
                    }

                    @Override
                    public ItemStack get(int slot) {
                        ItemStack held = frame.getItem();
                        return held.getType() == Material.AIR ? null : held;
                    }

                    @Override
                    public void set(int slot, ItemStack stack) {
                        frame.setItem(stack, false);
                    }
                }, null);
            }
            return new SurfaceOrSkip(null, "Transactions on " + containerType
                    + " entities cannot be reversed yet.");
        }
        return new SurfaceOrSkip(null, "Target is no longer a container.");
    }

    private static EntityType entityContainerType(String containerType) {
        if (containerType == null) {
            return null;
        }
        try {
            return EntityType.valueOf(containerType);
        } catch (IllegalArgumentException ex) {
            return null;   // a block material name - the block path already ran
        }
    }

    // Carts drift; frames and stands do not. 8 blocks keeps casual cart
    // motion in range without grabbing an unrelated cart two farms over.
    private static Entity nearestEntityOfType(World world, BlockLocation loc, EntityType type) {
        org.bukkit.Location center = new org.bukkit.Location(
                world, loc.x() + 0.5, loc.y() + 0.5, loc.z() + 0.5);
        Entity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Entity entity : world.getNearbyEntities(center, 8, 8, 8,
                e -> e.getType() == type)) {
            double distance = entity.getLocation().distanceSquared(center);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = entity;
            }
        }
        return best;
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
        Optional<RollbackEffectHandler> handler = handlerLookup.apply(effect.type());
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
        if (!includeEntities) {
            return new RollbackResult.Skipped(effect, new RollbackReason.NotSupported(ENTITIES_SKIP));
        }
        Optional<World> world = BlockLocations.resolveWorld(effect.location());
        if (world.isEmpty()) {
            return new RollbackResult.Skipped(effect, new RollbackReason.InvalidLocation(effect.location()));
        }
        Location location = new Location(world.get(),
                effect.location().x() + 0.5, effect.location().y(), effect.location().z() + 0.5);
        // Hostile mobs are never resurrected (#284), NBT snapshot or not:
        // nobody rolls back a slain zombie horde on purpose, and the killer
        // filter upstream still lets player-killed passives (pets, villagers,
        // livestock) come back.
        if (isHostile(effect.entityType())) {
            return new RollbackResult.Skipped(effect, new RollbackReason.NotSupported(
                    "Hostile mobs are not resurrected."));
        }
        // Full-NBT resurrection when a snapshot exists. In practice it
        // rarely does: Paper's serializeEntity rejects dying entities,
        // so death records ship with null NBT — hence the
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

    // Best-effort hostility check via the entity type's API class. Any
    // lookup failure (unknown type, registry not ready) resolves to NOT
    // hostile so the killer filter upstream remains the deciding gate.
    private static boolean isHostile(String entityTypeName) {
        if (entityTypeName == null || entityTypeName.isBlank()) {
            return false;
        }
        try {
            EntityType type = org.bukkit.Registry.ENTITY_TYPE.get(
                    org.bukkit.NamespacedKey.minecraft(
                            entityTypeName.toLowerCase(Locale.ROOT)));
            return type != null && type.getEntityClass() != null
                    && (org.bukkit.entity.Monster.class.isAssignableFrom(type.getEntityClass())
                        || org.bukkit.entity.Boss.class.isAssignableFrom(type.getEntityClass()));
        } catch (Throwable ignored) {
            return false;
        }
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
                    effect.entityType().toLowerCase(Locale.ROOT));
            EntityType type =
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
        if (!includeEntities) {
            return new RollbackResult.Skipped(effect, new RollbackReason.NotSupported(ENTITIES_SKIP));
        }
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
            // Resurrection almost always minted a fresh uuid; the undo
            // reference carries the (original -> fresh) pair (#294).
            UUID resurrected = entityAliases.get(entityId);
            if (resurrected != null) {
                entity = Bukkit.getEntity(resurrected);
            }
        }
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

        if (isProtectedLive(world.get(), effect.location().x(), effect.location().y(), effect.location().z())) {
            return new RollbackResult.Skipped(effect, new RollbackReason.NotSupported(PROTECTED_SKIP));
        }
        // Force-overwrite via applySnapshot (the Bukkit slow path) — same
        // contract as forceWriteCell.
        Block block = world.get().getBlockAt(effect.location().x(), effect.location().y(), effect.location().z());
        applySnapshot(block, effect.replacement());
        // applySnapshot writes with applyPhysics=false; re-arm the fluid
        // engine around the cell (#270).
        FluidTickScheduler.touchSingle(world.get(),
                effect.location().x(), effect.location().y(), effect.location().z());
        return appliedWithInverse(effect);
    }

    private RollbackResult applyContainerSlotWrite(RollbackEffect.ContainerSlotWrite effect) {
        if (!includeContainers) {
            return new RollbackResult.Skipped(effect, new RollbackReason.NotSupported(CONTAINERS_SKIP));
        }
        Optional<World> world = BlockLocations.resolveWorld(effect.location());
        if (world.isEmpty()) {
            return new RollbackResult.Skipped(effect, new RollbackReason.InvalidLocation(effect.location()));
        }
        SurfaceOrSkip resolved = resolveContainerSurface(
                world.get(), effect.location(), effect.containerType());
        if (resolved.surface() == null) {
            return new RollbackResult.Skipped(effect,
                    new RollbackReason.NotSupported(resolved.skipReason()));
        }
        SlotSurface surface = resolved.surface();

        // slot = -1 rows exist and stay benign skips: cursor-held bundle
        // transactions record -1 by design (there is no container slot),
        // and shift-click deposits recorded -1 before #268 - those legacy
        // rows live in every store until retention ages them out. The
        // recorded container may also be wider than the live one (chest ->
        // hopper downgrade). Range-check before getItem to avoid
        // IndexOutOfBoundsException.
        int slot = effect.slot();
        int size = surface.size();
        if (slot < 0 || slot >= size) {
            return new RollbackResult.Skipped(effect,
                    new RollbackReason.NotSupported(
                            "Slot " + slot + " out of range for container (size " + size + ")."));
        }

        ItemStack current = surface.get(slot);
        if (!matches(effect.expectedCurrent(), effect.slot(), current)) {
            return new RollbackResult.Skipped(effect, new RollbackReason.Guarded("Container slot changed."));
        }

        StoredItem replacement = effect.replacement();
        surface.set(effect.slot(), replacement == null ? null : ItemSerialization.decode(replacement.data()));
        surface.flush();
        StoredItem inverseCurrent = ItemSerialization.storedItem(effect.slot(), current);
        RollbackEffect inverse = new RollbackEffect.ContainerSlotWrite(
                effect.location(), effect.slot(), replacement, inverseCurrent, effect.containerType());
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
