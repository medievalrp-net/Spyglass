package net.medievalrp.omniscience2.plugin.worldedit;

import java.util.logging.Logger;
import net.medievalrp.omniscience2.plugin.listener.RecordingSupport;
import net.medievalrp.omniscience2.plugin.pipeline.Recorder;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Wires the {@link WorldEditSubscriber} to WorldEdit's own enable /
 * disable lifecycle. Before this, Omniscience2 only tried to hook WE
 * during its own onEnable — if WE loaded later (e.g. reload, or
 * /plugman load), edits weren't captured until a full server restart.
 *
 * <p>The listener is always registered on Omniscience2 startup. If WE
 * is already present at that time, {@code Omniscience2Plugin.onEnable}
 * installs the subscriber directly and hands it here; otherwise the
 * field stays null until a {@link PluginEnableEvent} for WE arrives.
 */
@ApiStatus.Internal
public final class WorldEditLifecycleListener implements Listener {

    private final Recorder recorder;
    private final RecordingSupport support;
    private final Logger logger;
    private WorldEditSubscriber subscriber;

    public WorldEditLifecycleListener(Recorder recorder, RecordingSupport support, Logger logger,
                                      @Nullable WorldEditSubscriber existing) {
        this.recorder = recorder;
        this.support = support;
        this.logger = logger;
        this.subscriber = existing;
    }

    @EventHandler
    public void onPluginEnable(PluginEnableEvent event) {
        if (!isWorldEdit(event.getPlugin().getName())) {
            return;
        }
        if (subscriber != null) {
            return;
        }
        try {
            subscriber = new WorldEditSubscriber(recorder, support, logger);
            subscriber.register();
            logger.info("Omniscience2: WorldEdit integration enabled after hot-load ("
                    + event.getPlugin().getName() + ").");
        } catch (Throwable thrown) {
            logger.warning("Omniscience2: late WE integration failed: " + thrown);
            subscriber = null;
        }
    }

    @EventHandler
    public void onPluginDisable(PluginDisableEvent event) {
        if (!isWorldEdit(event.getPlugin().getName())) {
            return;
        }
        if (subscriber == null) {
            return;
        }
        try {
            subscriber.unregister();
        } catch (Throwable ignored) {
        }
        subscriber = null;
        logger.info("Omniscience2: WorldEdit integration torn down ("
                + event.getPlugin().getName() + ").");
    }

    /**
     * The currently-live subscriber, if any. Used by
     * {@link net.medievalrp.omniscience2.plugin.Omniscience2Plugin#onDisable()}
     * for a final unregister pass on server shutdown.
     */
    @Nullable
    public WorldEditSubscriber currentSubscriber() {
        return subscriber;
    }

    private static boolean isWorldEdit(String name) {
        return "WorldEdit".equals(name) || "FastAsyncWorldEdit".equals(name);
    }
}
