package net.medievalrp.spyglass.plugin.util;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.BitSet;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;

/**
 * Sends a real {@code ClientboundLevelChunkWithLightPacket} to every
 * player in the world. Replaces {@link World#refreshChunk(int, int)}
 * which is deprecated and effectively a no-op on modern Paper.
 *
 * <p>Player iteration uses {@link World#getPlayers()} — broadcast to
 * all in-world players, no view-distance filter. Two earlier revisions
 * tried to be smarter and both false-negatived in production: Paper's
 * {@code Chunk#getPlayersSeeingChunk()} returned 0 viewers during a
 * freshly-triggered rollback even when the trigger player WAS in
 * range, and a manual view-distance filter on {@code world.getPlayers()}
 * excluded the trigger when its server-side location was stale. The
 * client silently drops chunk packets for chunks it doesn't have
 * loaded, so over-sending is cheap; false negatives are catastrophic
 * (rollback "looks broken" because the visual doesn't snap).
 *
 * <p>Packet construction + delivery use NMS via reflection (the
 * plugin builds against paper-api only — no paperweight-userdev). On
 * any reflection failure the resender disables itself for the rest
 * of the session and falls back to {@code World.refreshChunk} so the
 * rollback still completes.
 */
@ApiStatus.Internal
public final class ChunkResender {

    private static final Logger LOG = Logger.getLogger(ChunkResender.class.getName());

    private static volatile boolean initialized;
    private static volatile boolean available;

    private static Method craftWorldGetHandle;
    private static Method serverLevelGetChunk;
    private static Method serverLevelGetLightEngine;
    private static Constructor<?> packetCtor;

    private static Method craftPlayerGetHandle;
    private static Field serverPlayerConnection;
    private static Method connectionSend;

    private ChunkResender() {
    }

    /**
     * Send a fresh chunk-data packet to every player tracking
     * {@code (cx, cz)} in {@code world}. No-op if no players are
     * tracking the chunk; falls through to
     * {@link World#refreshChunk(int, int)} on any reflection failure.
     */
    public static void resend(World world, int cx, int cz) {
        if (world == null) {
            return;
        }
        if (!initialized) {
            init();
        }
        if (!available) {
            fallbackRefresh(world, cx, cz);
            return;
        }
        // Send to every player in the world. The earlier revision tried
        // {@link org.bukkit.Chunk#getPlayersSeeingChunk()} (returned 0
        // during freshly-triggered rollbacks) and a view-distance filter
        // on {@code world.getPlayers()} (false-negatived if a player's
        // server-side location was stale). Over-sending is cheap — the
        // client silently drops chunk packets for chunks it doesn't
        // have loaded — and false negatives are the worst possible
        // outcome (the rollback "looks broken" because the visual
        // doesn't snap). Real servers rarely have so many players that
        // broadcasting one extra packet per chunk-resend matters.
        java.util.List<Player> viewers = world.getPlayers();
        if (viewers.isEmpty()) {
            return;
        }
        try {
            Object serverLevel = craftWorldGetHandle.invoke(world);
            Object levelChunk = serverLevelGetChunk.invoke(serverLevel, cx, cz);
            if (levelChunk == null) {
                return;
            }
            Object lightEngine = serverLevelGetLightEngine.invoke(serverLevel);
            Object packet = packetCtor.newInstance(
                    levelChunk, lightEngine, (BitSet) null, (BitSet) null);

            for (Player viewer : viewers) {
                Object serverPlayer;
                try {
                    serverPlayer = craftPlayerGetHandle.invoke(viewer);
                } catch (Throwable ignored) {
                    continue;
                }
                if (serverPlayer == null) {
                    continue;
                }
                Object connection = serverPlayerConnection.get(serverPlayer);
                if (connection == null) {
                    continue;
                }
                connectionSend.invoke(connection, packet);
            }
        } catch (Throwable t) {
            // Reflection blew up at runtime — disable for the rest of
            // the session and fall through so the rollback still
            // completes (just visually grainy).
            available = false;
            LOG.log(Level.WARNING,
                    "Spyglass ChunkResender failed; falling back to refreshChunk", t);
            fallbackRefresh(world, cx, cz);
        }
    }

    private static void fallbackRefresh(World world, int cx, int cz) {
        try {
            world.refreshChunk(cx, cz);
        } catch (Throwable ignored) {
            // refreshChunk is allowed to throw on unloaded chunks /
            // non-Bukkit envs; nothing useful to do.
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
            serverLevelGetLightEngine = serverLevel.getMethod("getLightEngine");

            Class<?> levelChunkClass = Class.forName("net.minecraft.world.level.chunk.LevelChunk");
            Class<?> lightEngineClass = Class.forName(
                    "net.minecraft.world.level.lighting.LevelLightEngine");
            Class<?> packetClass = Class.forName(
                    "net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket");
            packetCtor = packetClass.getConstructor(
                    levelChunkClass, lightEngineClass, BitSet.class, BitSet.class);

            Class<?> craftPlayer = Class.forName("org.bukkit.craftbukkit.entity.CraftPlayer");
            craftPlayerGetHandle = craftPlayer.getMethod("getHandle");

            Class<?> serverPlayer = Class.forName("net.minecraft.server.level.ServerPlayer");
            serverPlayerConnection = serverPlayer.getField("connection");

            Class<?> connectionClass = Class.forName(
                    "net.minecraft.server.network.ServerGamePacketListenerImpl");
            Class<?> packetInterface = Class.forName("net.minecraft.network.protocol.Packet");
            connectionSend = connectionClass.getMethod("send", packetInterface);

            available = true;
        } catch (Throwable t) {
            available = false;
            LOG.log(Level.WARNING,
                    "Spyglass ChunkResender unavailable (paper-api/NMS mismatch); "
                            + "rollbacks will fall back to refreshChunk and may render grain-by-grain. "
                            + "Cause: " + t,
                    t);
        }
    }
}
