package net.medievalrp.spyglass.plugin.util;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.World;
import org.jetbrains.annotations.ApiStatus;

/**
 * Recomputes block + sky light for chunks a rollback wrote straight into
 * the {@code LevelChunkSection} palette via {@link ChunkDirectWriter}.
 *
 * <p>The direct palette write deliberately bypasses {@code
 * LevelChunk.setBlockState}, and with it the light-engine notification —
 * so the server's stored light for those cells stays exactly as it was
 * before the rollback. {@link ChunkResender} then ships a chunk packet
 * built from that stale light, and the client renders a block-shaped
 * shadow (or a wrongly-lit gap) over the rolled region until something
 * external (a neighbouring block update, a chunk reload) forces a relight.
 * On a WorldEdit-selection rollback — a large contiguous volume — that
 * stale-light artifact is glaringly visible.
 *
 * <p>This class drives Paper's Starlight relight: {@code
 * ThreadedLevelLightEngine.starlight$serverRelightChunks(Collection,
 * Consumer, IntConsumer)}, the same entry point WorldEdit's {@code
 * //fixlighting} and FAWE use. It fully recomputes light for the given
 * chunks off the main thread and reports each chunk back as it lands, so
 * the caller can resend it — Starlight does not broadcast to clients
 * itself. The caller is expected to {@link ChunkResender#resend} each
 * chunk from the {@code onChunkRelit} callback.
 *
 * <p>Reflective NMS access — the plugin builds against paper-api only.
 * On any reflection failure the relighter disables itself for the rest of
 * the session and {@link #relight} no-ops (returns {@code false}); the
 * rollback still completes, lighting just stays stale as it did before.
 */
@ApiStatus.Internal
public final class ChunkRelighter {

    private static final Logger LOG = Logger.getLogger(ChunkRelighter.class.getName());

    private static volatile boolean initialized;
    private static volatile boolean available;

    private static Method craftWorldGetHandle;
    private static Method serverLevelGetLightEngine;
    private static Method serverRelightChunks;
    private static Constructor<?> chunkPosCtor;
    // ChunkPos coordinate read differs by version: public final fields
    // x/z on 1.21.x, record accessors x()/z() on 26.x. Resolve whichever
    // this build exposes; exactly one pair is non-null when available.
    private static Field chunkPosXField;
    private static Field chunkPosZField;
    private static Method chunkPosXMethod;
    private static Method chunkPosZMethod;

    private ChunkRelighter() {
    }

    /** Callback invoked with each chunk's coords as Starlight relights it. */
    @FunctionalInterface
    public interface ChunkRelit {
        void accept(int cx, int cz);
    }

    /**
     * Pack a chunk coordinate into a long the same way {@code
     * RollbackService}'s warmed-chunk set does: {@code cx} high, {@code
     * cz} low. Sharing the scheme lets that set feed {@link #relight}
     * untouched.
     */
    public static long packChunk(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    public static int chunkX(long key) {
        return (int) (key >> 32);
    }

    public static int chunkZ(long key) {
        return (int) key;
    }

    /** Whether the Starlight relight path resolved on this server. */
    public static boolean isAvailable() {
        if (!initialized) {
            init();
        }
        return available;
    }

    /**
     * Asynchronously recompute light for {@code chunkKeys} (packed via
     * {@link #packChunk}) in {@code world}. As each chunk's light is
     * recomputed, {@code onChunkRelit} fires with its coords — on a
     * Starlight thread, so hop to the main thread before touching Bukkit.
     * Returns {@code false} without scheduling anything if the relight API
     * is unavailable. Call on the main thread.
     */
    public static boolean relight(World world, long[] chunkKeys, ChunkRelit onChunkRelit) {
        if (world == null || chunkKeys == null || chunkKeys.length == 0) {
            return false;
        }
        if (!initialized) {
            init();
        }
        if (!available) {
            return false;
        }
        try {
            Object serverLevel = craftWorldGetHandle.invoke(world);
            Object lightEngine = serverLevelGetLightEngine.invoke(serverLevel);
            List<Object> chunkPosList = new ArrayList<>(chunkKeys.length);
            for (long key : chunkKeys) {
                chunkPosList.add(chunkPosCtor.newInstance(chunkX(key), chunkZ(key)));
            }
            Consumer<Object> perChunk = chunkPos -> {
                if (onChunkRelit == null) {
                    return;
                }
                try {
                    int cx;
                    int cz;
                    if (chunkPosXField != null) {
                        cx = chunkPosXField.getInt(chunkPos);
                        cz = chunkPosZField.getInt(chunkPos);
                    } else {
                        cx = (int) chunkPosXMethod.invoke(chunkPos);
                        cz = (int) chunkPosZMethod.invoke(chunkPos);
                    }
                    onChunkRelit.accept(cx, cz);
                } catch (Throwable ignored) {
                    // A failed coord read only means this chunk isn't
                    // resent with fresh light here; the server-side light
                    // is still corrected, so the next natural chunk send
                    // (relog / re-track) carries the right values.
                }
            };
            // Completion total — per-chunk resends already cover client
            // visibility, so nothing to do on the final tally.
            IntConsumer onDone = count -> { };
            serverRelightChunks.invoke(lightEngine, chunkPosList, perChunk, onDone);
            return true;
        } catch (Throwable t) {
            available = false;
            LOG.log(Level.WARNING,
                    "Spyglass ChunkRelighter failed; rolled chunks keep stale lighting "
                            + "until a natural relight. Cause: " + t, t);
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

            // getLightEngine() is inherited from Level; the runtime instance
            // on a server is always the ThreadedLevelLightEngine that carries
            // the Starlight relight method. ChunkResender resolves it the same
            // way.
            Class<?> serverLevel = Class.forName("net.minecraft.server.level.ServerLevel");
            serverLevelGetLightEngine = serverLevel.getMethod("getLightEngine");

            Class<?> threadedLight = Class.forName(
                    "net.minecraft.server.level.ThreadedLevelLightEngine");
            // starlight$serverRelightChunks(Collection<ChunkPos>,
            // Consumer<ChunkPos>, IntConsumer) — Moonrise/Starlight patch,
            // present on every modern Paper build (verified 1.21.8 + 26.x).
            serverRelightChunks = threadedLight.getMethod("starlight$serverRelightChunks",
                    Collection.class, Consumer.class, IntConsumer.class);

            Class<?> chunkPos = Class.forName("net.minecraft.world.level.ChunkPos");
            chunkPosCtor = chunkPos.getConstructor(int.class, int.class);
            // Public fields on 1.21.x; record accessors on 26.x (where
            // ChunkPos became a record and the fields went private).
            try {
                chunkPosXField = chunkPos.getField("x");
                chunkPosZField = chunkPos.getField("z");
            } catch (NoSuchFieldException noPublicFields) {
                chunkPosXField = null;
                chunkPosZField = null;
                chunkPosXMethod = chunkPos.getMethod("x");
                chunkPosZMethod = chunkPos.getMethod("z");
            }

            available = true;
        } catch (Throwable t) {
            available = false;
            LOG.log(Level.WARNING,
                    "Spyglass ChunkRelighter unavailable (no Starlight relight API; rolled "
                            + "chunks keep stale lighting until a natural relight). Cause: " + t, t);
        }
    }
}
