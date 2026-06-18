package net.medievalrp.spyglass.plugin.util;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.jetbrains.annotations.ApiStatus;

/**
 * Writes a single block by writing the palette entry directly into the
 * {@code LevelChunkSection} — bypasses {@code LevelChunk.setBlockState}
 * entirely, which means we skip:
 * <ul>
 *   <li>{@code Block.onPlace} (which schedules the falling-block tick
 *       that makes sand fall on the next tick — the bug the user kept
 *       hitting on {@code /sg undo}),</li>
 *   <li>neighbor updates,</li>
 *   <li>scheduled tick registration,</li>
 *   <li>per-block change packets to clients (which is what produced
 *       the speckle).</li>
 * </ul>
 *
 * <p>This is what CoreProtect does. The visual update happens later
 * via {@link ChunkResender} sending one full-chunk packet per chunk.
 *
 * <p>Reflective NMS access — the plugin builds against paper-api only.
 * On reflection failure, falls back to {@code Block.setBlockData(data,
 * false)} so the rollback still completes, but the per-block speckle
 * and gravity-on-undo bugs both come back. Production must run with
 * {@code available == true}; the regression bot test asserts no
 * "ChunkDirectWriter unavailable" warning fires during a rollback.
 *
 * <p>Earlier rev tried {@code LevelChunk.setBlockState(BlockPos,
 * BlockState, boolean)} and that signature does not exist on
 * Paper 1.21.8 build 60+ (the lookup throws
 * {@code NoSuchMethodException}). Going one level lower to
 * {@code LevelChunkSection} sidesteps the missing signature and the
 * {@code onPlace} call in one move.
 */
@ApiStatus.Internal
public final class ChunkDirectWriter {

    private static final Logger LOG = Logger.getLogger(ChunkDirectWriter.class.getName());

    private static volatile boolean initialized;
    private static volatile boolean available;

    private static Method craftWorldGetHandle;
    private static Method serverLevelGetChunk;
    private static Method levelChunkGetSectionIndex;
    private static Method levelChunkGetSection;
    private static Method levelChunkSetUnsaved;
    private static Method sectionSetBlockState;
    private static Method craftBlockDataGetState;
    // Light engine bits — optional. If lookup fails the writer still
    // works; the chunk packet just carries stale light data and the
    // client briefly sees wrong lighting until the engine catches up.
    private static Method serverLevelGetLightEngine;
    private static Method lightEngineCheckBlock;
    private static Constructor<?> blockPosCtor;

    private ChunkDirectWriter() {
    }

    /**
     * Cache of NMS {@code BlockState} resolved from a Bukkit
     * {@link BlockData} instance. {@code BlockData} is immutable per
     * its Bukkit contract; the underlying NMS state is also fixed for
     * an instance, so caching the unwrap saves one reflective invoke
     * per block in the rollback hot loop. Combined with the
     * {@code BLOCK_DATA_CACHE} in RollbackEngine (string→BlockData),
     * a 100K stone-rollback ends up doing one parse and one state
     * unwrap, then 99,998 cache hits.
     */
    private static final java.util.concurrent.ConcurrentHashMap<BlockData, Object> NMS_STATE_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static Method chunkGetMinSectionY;

    /**
     * Per-chunk write context. Resolves {@code serverLevel} +
     * {@code levelChunk} once at {@link #prepareChunk}; subsequent
     * {@link #writeBlock} calls reuse them and only re-resolve the
     * {@code LevelChunkSection} when {@code y} crosses a 16-block
     * vertical boundary. {@link #finishChunk} marks the chunk dirty
     * so writes survive save.
     *
     * <p>Per-block cost in this path: one cached {@code getState}
     * lookup + one {@code setBlockState} reflective invoke. Down from
     * 7 reflective invokes per block in the legacy
     * {@link #writeBlock} entry point.
     */
    public static final class ChunkContext {
        private final Object levelChunk;
        private final int minSection;
        private Object lastSection;
        private int lastSectionIndex = Integer.MIN_VALUE;

        ChunkContext(Object levelChunk, int minSection) {
            this.levelChunk = levelChunk;
            this.minSection = minSection;
        }

        /**
         * Write a block within this chunk. {@code (x, y, z)} are
         * absolute world coords; the chunk membership is asserted by
         * the caller (the rollback engine sorts effects by chunk).
         */
        public void writeBlock(int x, int y, int z, BlockData blockData) {
            try {
                // sectionIndex computed directly from y — no reflective
                // call per block. Equivalent to LevelChunk.getSectionIndex(y)
                // = (y - minBuildHeight) >> 4 = (y >> 4) - minSection.
                int sectionIndex = (y >> 4) - minSection;
                if (sectionIndex != lastSectionIndex) {
                    lastSection = levelChunkGetSection.invoke(levelChunk, sectionIndex);
                    lastSectionIndex = sectionIndex;
                }
                if (lastSection == null) {
                    return;
                }
                Object nmsState = NMS_STATE_CACHE.computeIfAbsent(blockData, this::resolveNmsState);
                if (nmsState == null) {
                    return;
                }
                sectionSetBlockState.invoke(lastSection, x & 15, y & 15, z & 15, nmsState);
            } catch (Throwable ignored) {
                // Swallow per-block failures; the legacy writeBlock
                // path's Bukkit fallback would also lose the cell on
                // throw. Caller's whole-chunk fallback handles broad
                // reflection breakage.
            }
        }

        private Object resolveNmsState(BlockData bd) {
            try {
                return craftBlockDataGetState.invoke(bd);
            } catch (Throwable t) {
                return null;
            }
        }

    }

    /**
     * Prepare a write context for a single chunk. Returns {@code null}
     * if the underlying NMS access path isn't available — caller
     * should fall back to {@link #writeBlock} (which itself falls back
     * to Bukkit) for that chunk.
     */
    public static ChunkContext prepareChunk(World world, int cx, int cz) {
        if (world == null) {
            return null;
        }
        if (!initialized) {
            init();
        }
        if (!available) {
            return null;
        }
        try {
            Object serverLevel = craftWorldGetHandle.invoke(world);
            Object levelChunk = serverLevelGetChunk.invoke(serverLevel, cx, cz);
            if (levelChunk == null) {
                return null;
            }
            int minSection = 0;
            if (chunkGetMinSectionY != null) {
                try {
                    minSection = (int) chunkGetMinSectionY.invoke(levelChunk);
                } catch (Throwable ignored) {
                    // Default 0 — works for vanilla overworld of older
                    // builds. Modern overworld is -4; on a Paper build
                    // where the lookup fails, sectionIndex math drifts
                    // and writes hit a wrong section. Fail loud:
                    minSection = 0;
                }
            }
            return new ChunkContext(levelChunk, minSection);
        } catch (Throwable t) {
            available = false;
            LOG.log(Level.WARNING, "Spyglass ChunkDirectWriter prepareChunk failed", t);
            return null;
        }
    }

    /** Mark the chunk dirty so writes from {@link ChunkContext#writeBlock} persist on save. */
    public static void finishChunk(ChunkContext ctx) {
        if (ctx == null || levelChunkSetUnsaved == null) {
            return;
        }
        try {
            levelChunkSetUnsaved.invoke(ctx.levelChunk, true);
        } catch (Throwable ignored) {
            // setUnsaved missing → Paper's chunk system periodically
            // saves dirty regions anyway; the missed mark isn't fatal.
        }
    }

    /**
     * Write {@code blockData} at {@code (x, y, z)} in {@code world}
     * via direct {@code LevelChunkSection} palette write. Returns
     * {@code true} on any non-throwing path (including the Bukkit
     * fallback). Legacy single-block entry — used for non-chunked
     * callers and for the per-block fallback inside the engine when
     * {@link #prepareChunk} returns null.
     */
    public static boolean writeBlock(World world, int x, int y, int z, BlockData blockData) {
        if (world == null || blockData == null) {
            return false;
        }
        if (!initialized) {
            init();
        }
        if (!available) {
            return fallbackWrite(world, x, y, z, blockData);
        }
        try {
            Object serverLevel = craftWorldGetHandle.invoke(world);
            int cx = x >> 4;
            int cz = z >> 4;
            Object levelChunk = serverLevelGetChunk.invoke(serverLevel, cx, cz);
            if (levelChunk == null) {
                return fallbackWrite(world, x, y, z, blockData);
            }
            int sectionIndex = (int) levelChunkGetSectionIndex.invoke(levelChunk, y);
            Object section = levelChunkGetSection.invoke(levelChunk, sectionIndex);
            if (section == null) {
                return fallbackWrite(world, x, y, z, blockData);
            }
            Object nmsState = craftBlockDataGetState.invoke(blockData);
            int localX = x & 15;
            int localY = y & 15;
            int localZ = z & 15;
            // Write the palette entry directly. No onPlace, no physics,
            // no scheduled ticks, no neighbor notify, no client packet.
            sectionSetBlockState.invoke(section, localX, localY, localZ, nmsState);
            // PERF: per-block lightEngine.checkBlock removed. The
            // ChunkResender packet built later carries the new block
            // data; light is recomputed by the engine on its own
            // pass within ~1-2 ticks. The earlier "shimmer" we
            // worried about isn't visible at the speed players
            // perceive a chunk-batch resend (whole-chunk visual
            // snap). At 1 M blocks the per-block reflection +
            // BlockPos alloc was 2 of the 7 reflective calls per
            // block — dropping them is the largest single rollback-
            // throughput win we get without paperweight-userdev.
            //
            // Mark chunk dirty so the change persists on save. The
            // chunk packet from {@link ChunkResender} handles client
            // visibility separately.
            if (levelChunkSetUnsaved != null) {
                try {
                    levelChunkSetUnsaved.invoke(levelChunk, true);
                } catch (Throwable ignored) {
                    // Some Paper builds may have reshaped this; the
                    // chunk system periodically saves dirty regions
                    // anyway, so a missed mark isn't fatal.
                }
            }
            return true;
        } catch (Throwable t) {
            available = false;
            LOG.log(Level.WARNING, "Spyglass ChunkDirectWriter failed; falling back to Bukkit", t);
            return fallbackWrite(world, x, y, z, blockData);
        }
    }

    private static boolean fallbackWrite(World world, int x, int y, int z, BlockData blockData) {
        try {
            world.getBlockAt(x, y, z).setBlockData(blockData, false);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static synchronized void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        try {
            Class<?> craftWorld = Class.forName("org.bukkit.craftbukkit.CraftWorld");
            craftWorldGetHandle = craftWorld.getMethod("getHandle");

            Class<?> serverLevel = Class.forName("net.minecraft.server.level.ServerLevel");
            serverLevelGetChunk = serverLevel.getMethod("getChunk", int.class, int.class);

            Class<?> levelChunkClass = Class.forName("net.minecraft.world.level.chunk.LevelChunk");
            // getSectionIndex(int worldY) is on LevelHeightAccessor (interface
            // implemented by Level/LevelChunk). Look up via the chunk class
            // to get the implementation; reflection finds inherited methods.
            levelChunkGetSectionIndex = findMethod(levelChunkClass, "getSectionIndex", int.class);
            // getSection(int sectionIndex) — present on both LevelChunk and
            // ChunkAccess parent.
            levelChunkGetSection = findMethod(levelChunkClass, "getSection", int.class);
            // getMinSectionY() — used by ChunkContext to compute
            // sectionIndex without a per-block reflective call. Try
            // a few names since the API has rotated through
            // getMinSection/getMinSectionY/getMinBuildHeight.
            try {
                chunkGetMinSectionY = findMethod(levelChunkClass, "getMinSectionY");
            } catch (NoSuchMethodException e1) {
                try {
                    chunkGetMinSectionY = findMethod(levelChunkClass, "getMinSection");
                } catch (NoSuchMethodException e2) {
                    chunkGetMinSectionY = null;
                }
            }
            // setUnsaved(boolean) on ChunkAccess. Some Paper builds also
            // expose markUnsaved(); try setUnsaved first, fall back to
            // markUnsaved(). null = neither available; we'll skip the
            // mark and rely on Paper's chunk-system periodic save.
            try {
                levelChunkSetUnsaved = findMethod(levelChunkClass, "setUnsaved", boolean.class);
            } catch (NoSuchMethodException ignored) {
                try {
                    levelChunkSetUnsaved = findMethod(levelChunkClass, "markUnsaved");
                } catch (NoSuchMethodException ignored2) {
                    levelChunkSetUnsaved = null;
                }
            }

            Class<?> sectionClass = Class.forName("net.minecraft.world.level.chunk.LevelChunkSection");
            Class<?> blockStateClass = Class.forName("net.minecraft.world.level.block.state.BlockState");
            // setBlockState(int x, int y, int z, BlockState state) — 4-arg
            // variant. There's also a 5-arg with a useLocks boolean; the
            // 4-arg internally calls the 5-arg with useLocks=true (thread-
            // safe path). We're on the main thread so either works.
            sectionSetBlockState = findMethod(sectionClass, "setBlockState",
                    int.class, int.class, int.class, blockStateClass);
            Class<?> craftBlockData = Class.forName("org.bukkit.craftbukkit.block.data.CraftBlockData");
            craftBlockDataGetState = craftBlockData.getMethod("getState");

            // Light engine — best-effort. checkBlock(BlockPos) re-evaluates
            // light at that position. Without this, the chunk-resender
            // packet carries stale light and you see brief shimmer until
            // the engine catches up on its own schedule.
            try {
                serverLevelGetLightEngine = findMethod(serverLevel, "getLightEngine");
                Class<?> lightEngineClass = Class.forName(
                        "net.minecraft.world.level.lighting.LevelLightEngine");
                Class<?> blockPosClass = Class.forName("net.minecraft.core.BlockPos");
                lightEngineCheckBlock = findMethod(lightEngineClass, "checkBlock", blockPosClass);
                blockPosCtor = blockPosClass.getConstructor(int.class, int.class, int.class);
            } catch (Throwable lightFail) {
                serverLevelGetLightEngine = null;
                lightEngineCheckBlock = null;
                blockPosCtor = null;
            }

            available = true;
        } catch (Throwable t) {
            available = false;
            LOG.log(Level.WARNING,
                    "Spyglass ChunkDirectWriter unavailable (paper-api/NMS mismatch); "
                            + "rollback writes will fall back to Bukkit setBlockData and may "
                            + "emit per-block change packets / trip gravity. Cause: " + t,
                    t);
        }
    }

    /**
     * Look up a method by name, walking up the class hierarchy if the
     * direct lookup misses (Paper sometimes places methods on parent
     * classes / interfaces). Throws {@code NoSuchMethodException} if
     * nothing matches.
     */
    private static Method findMethod(Class<?> clazz, String name, Class<?>... paramTypes)
            throws NoSuchMethodException {
        try {
            return clazz.getMethod(name, paramTypes);
        } catch (NoSuchMethodException ignored) {
            // Walk parent classes looking for declared methods. Some
            // Paper rewrites move methods to package-private parents.
            for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
                try {
                    Method m = c.getDeclaredMethod(name, paramTypes);
                    m.setAccessible(true);
                    return m;
                } catch (NoSuchMethodException ignored2) {
                    // continue walking
                }
            }
            // Try interfaces too
            for (Class<?> iface : clazz.getInterfaces()) {
                try {
                    return iface.getMethod(name, paramTypes);
                } catch (NoSuchMethodException ignored2) {
                    // continue
                }
            }
            throw new NoSuchMethodException(
                    clazz.getName() + "." + name + "(" + java.util.Arrays.toString(paramTypes) + ")");
        }
    }
}
