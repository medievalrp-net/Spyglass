package net.medievalrp.spyglass.plugin.util;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.World;
import org.jetbrains.annotations.ApiStatus;

/**
 * Post-write fluid pass for rollback (#270), the fluid analogue of
 * {@link ChunkRelighter}. {@link ChunkDirectWriter} deliberately skips
 * scheduled-tick registration and neighbor updates, and Minecraft fluid
 * flow is driven entirely by scheduled ticks - so a rolled-back area
 * was left with orphaned flowing water that never drains and dry
 * pockets beside sources that never fill, until some unrelated block
 * update forced a re-evaluation.
 *
 * <p>After each chunk's writes land, {@link Pass#touch} is called once
 * per written cell: the cell and its six orthogonal neighbors (which
 * includes the one-block shell just outside the rolled area - those
 * neighbors were never notified either) are checked, and every fluid
 * found gets a scheduled fluid tick. The fluid engine then re-simulates
 * the region to its natural equilibrium. Cost is proportional to
 * fluid-adjacent cells; a dirt-only rollback pays seven cheap fluid
 * state reads per cell and schedules nothing.
 *
 * <p>Deliberately scoped to fluids: blanket neighbor updates over the
 * area would re-trigger {@code Block.onPlace} for sand, gravel and
 * concrete powder and reintroduce the falling-block bug
 * {@link ChunkDirectWriter} exists to avoid. Scheduling a fluid tick
 * triggers nothing immediately; the re-evaluation runs on later ticks
 * through the vanilla fluid engine.
 *
 * <p>Reflective NMS access, same contract as {@link ChunkDirectWriter}:
 * built against paper-api only, resolved once, and on lookup failure
 * every pass is a no-op (the pre-#270 behavior) with one WARN.
 *
 * <p>Known limitation, documented in COMMANDS.md: re-simulation drives
 * water to its natural equilibrium for the restored terrain, which is
 * not necessarily the exact fluid layout at the rollback timestamp -
 * flow was never recorded (deliberately: per-cell-per-tick volume, see
 * HopperTransferListener for the same call).
 */
@ApiStatus.Internal
public final class FluidTickScheduler {

    private static final Logger LOG = Logger.getLogger(FluidTickScheduler.class.getName());

    /** Fallback delay when the fluid's own tick delay is unavailable. */
    private static final int DEFAULT_DELAY_TICKS = 5;

    private static volatile boolean initialized;
    private static volatile boolean available;

    private static Method craftWorldGetHandle;
    private static Constructor<?> blockPosCtor;
    private static Method levelGetFluidState;
    private static Method fluidStateIsEmpty;
    private static Method fluidStateGetType;
    private static Method levelScheduleTick;
    private static Method fluidGetTickDelay;

    private FluidTickScheduler() {
    }

    private static void init(World world) {
        if (initialized) {
            return;
        }
        synchronized (FluidTickScheduler.class) {
            if (initialized) {
                return;
            }
            try {
                // Every class is derived from the live object graph, never
                // Class.forName: Paper's plugin remapper rewrites reflective
                // name constants for spigot-mapped plugins, and the spigot
                // name "material.Fluid" maps to Mojang's FluidState, which
                // silently corrupts a forName-based lookup. Method scans by
                // name and shape are immune (same approach as
                // ChunkDirectWriter's object-graph navigation).
                craftWorldGetHandle = world.getClass().getMethod("getHandle");
                Object level = craftWorldGetHandle.invoke(world);
                // scheduleTick(BlockPos, Fluid, int) - the fluid overload of
                // ScheduledTickAccess; discriminated from the Block overload
                // by the parameter's simple name.
                for (Method m : level.getClass().getMethods()) {
                    if (!"scheduleTick".equals(m.getName())) {
                        continue;
                    }
                    Class<?>[] p = m.getParameterTypes();
                    if (p.length == 3 && p[2] == int.class
                            && p[1].getSimpleName().equals("Fluid")) {
                        levelScheduleTick = m;
                        break;
                    }
                }
                if (levelScheduleTick == null) {
                    throw new NoSuchMethodException("scheduleTick(BlockPos, Fluid, int)");
                }
                Class<?> blockPos = levelScheduleTick.getParameterTypes()[0];
                Class<?> fluid = levelScheduleTick.getParameterTypes()[1];
                blockPosCtor = blockPos.getConstructor(int.class, int.class, int.class);
                levelGetFluidState = level.getClass().getMethod("getFluidState", blockPos);
                Class<?> fluidState = levelGetFluidState.getReturnType();
                fluidStateIsEmpty = fluidState.getMethod("isEmpty");
                fluidStateGetType = fluidState.getMethod("getType");
                // Optional nicety: the fluid's own delay (water 5, lava 30).
                // Missing lookup falls back to a constant; an early tick only
                // re-evaluates sooner and the engine re-schedules itself.
                try {
                    for (Method m : fluid.getMethods()) {
                        if ("getTickDelay".equals(m.getName())
                                && m.getParameterCount() == 1
                                && m.getParameterTypes()[0].isInstance(level)) {
                            fluidGetTickDelay = m;
                            break;
                        }
                    }
                } catch (RuntimeException ignored) {
                    fluidGetTickDelay = null;
                }
                available = true;
            } catch (ReflectiveOperationException | RuntimeException ex) {
                available = false;
                LOG.log(Level.WARNING, "FluidTickScheduler unavailable; rolled-back fluids "
                        + "will not re-flow until an external block update: " + ex);
            }
            initialized = true;
        }
    }

    /**
     * Start a pass for one chunk's writes. Call on the MAIN thread, after
     * the chunk's palette writes finished. Returns a no-op pass when the
     * reflection handles are unavailable.
     */
    public static Pass begin(World world) {
        init(world);
        if (!available) {
            return Pass.NOOP;
        }
        try {
            return new Pass(craftWorldGetHandle.invoke(world));
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return Pass.NOOP;
        }
    }

    /** Single-cell convenience for the non-batched apply path. */
    public static void touchSingle(World world, int x, int y, int z) {
        begin(world).touch(x, y, z);
    }

    /** One post-write pass; dedupes scheduling within its lifetime. */
    public static final class Pass {

        static final Pass NOOP = new Pass(null);

        private final Object level;
        private final Set<Long> visited;

        private Pass(Object level) {
            this.level = level;
            this.visited = level == null ? null : new HashSet<>();
        }

        /**
         * Consider one written cell: the cell itself plus its six
         * orthogonal neighbors. Any fluid (including waterlogged
         * blocks) gets a scheduled fluid tick.
         */
        public void touch(int x, int y, int z) {
            if (level == null) {
                return;
            }
            consider(x, y, z);
            consider(x + 1, y, z);
            consider(x - 1, y, z);
            consider(x, y + 1, z);
            consider(x, y - 1, z);
            consider(x, y, z + 1);
            consider(x, y, z - 1);
        }

        private void consider(int x, int y, int z) {
            // Sections span the full build height either way; vanilla
            // getFluidState returns the empty state out of bounds.
            long key = (((long) x & 0x3FFFFFFL) << 38) | (((long) z & 0x3FFFFFFL) << 12) | (y & 0xFFFL);
            if (!visited.add(key)) {
                return;
            }
            try {
                Object pos = blockPosCtor.newInstance(x, y, z);
                Object fluidState = levelGetFluidState.invoke(level, pos);
                if ((boolean) fluidStateIsEmpty.invoke(fluidState)) {
                    return;
                }
                Object fluid = fluidStateGetType.invoke(fluidState);
                int delay = DEFAULT_DELAY_TICKS;
                if (fluidGetTickDelay != null) {
                    try {
                        delay = (int) fluidGetTickDelay.invoke(fluid, level);
                    } catch (ReflectiveOperationException | RuntimeException ignored) {
                        // keep the fallback delay
                    }
                }
                levelScheduleTick.invoke(level, pos, fluid, delay);
            } catch (ReflectiveOperationException | RuntimeException ex) {
                // Best-effort per cell: a missed tick means that one cell
                // keeps the pre-#270 behavior (fixed by any block update).
                LOG.log(Level.FINE, "Fluid tick scheduling failed at "
                        + x + "," + y + "," + z, ex);
            }
        }
    }
}
