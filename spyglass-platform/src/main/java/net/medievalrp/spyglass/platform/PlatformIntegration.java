package net.medievalrp.spyglass.platform;

import java.util.Map;
import java.util.logging.Level;
import net.crusalis.platform.sdk.api.Platform;
import net.medievalrp.spyglass.api.event.EventRecord;
import net.medievalrp.spyglass.api.event.RecordCommittedEvent;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * The Crusalis platform integration: the only class the Spyglass plugin names.
 *
 * <p>It is started reflectively, by {@code CrusalisPlatformLoader} in the plugin, and
 * only after the loader has confirmed both that {@code integrations.crusalis-platform}
 * is enabled and that the SDK's API is on the classpath. That is the whole reason the
 * entry point is a static method returning {@link AutoCloseable} rather than a typed
 * constructor: no signature here mentions a Crusalis type, so a server without the SDK
 * loads no class from this module at all — not even this one.
 *
 * <p>What it does, in one sentence: listens for {@link RecordCommittedEvent}, maps each
 * record to a Crusalis event type and publishes it through the SDK's hot path, with
 * WorldEdit operations and explosions aggregated into one event each.
 */
public final class PlatformIntegration implements Listener, AutoCloseable {

    /** How often the WorldEdit aggregator is asked to flush finished operations. */
    private static final long FLUSH_PERIOD_TICKS = 20L;

    private final JavaPlugin plugin;
    private final PlatformPublisher publisher;
    private final WorldEditAggregator worldEdit;
    private final ExplosionAggregator explosions;
    private BukkitTask flushTask;

    /**
     * Starts the integration.
     *
     * <p>Called by reflection. The signature is JDK and Bukkit types only, by design:
     * see the class javadoc.
     *
     * @param plugin the Spyglass plugin instance
     * @param rawSettings the {@code integrations.crusalis-platform} block as a map
     * @return a handle to close on disable, or null when the platform is not available
     */
    public static AutoCloseable start(JavaPlugin plugin, Map<String, Object> rawSettings) {
        PlatformSettings settings = PlatformSettings.from(rawSettings);
        if (!settings.enabled()) {
            return null;
        }
        Platform platform = Bukkit.getServicesManager().load(Platform.class);
        if (platform == null) {
            // The SDK's classes are present — the loader checked — but its plugin did
            // not register the service, which means it failed to start. One line, and
            // Spyglass carries on: its own storage is the record of record.
            plugin.getLogger().info("CrusalisPlatformSDK is installed but registered no"
                    + " Platform service; the Crusalis integration stays off.");
            return null;
        }
        PlatformIntegration integration = new PlatformIntegration(plugin, platform, settings);
        integration.register();
        plugin.getLogger().info("Crusalis platform integration enabled: publishing to "
                + platform.env() + " as " + platform.serverId() + " ("
                + ActionMap.keys().size() + " mapped actions, "
                + ActionMap.types().size() + " event types).");
        return integration;
    }

    private PlatformIntegration(JavaPlugin plugin, Platform platform, PlatformSettings settings) {
        this.plugin = plugin;
        this.publisher = new PlatformPublisher(platform, settings,
                message -> plugin.getLogger().log(Level.WARNING, message));
        this.worldEdit = new WorldEditAggregator(publisher);
        this.explosions = new ExplosionAggregator(publisher);
    }

    private void register() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getPluginManager().registerEvents(explosions, plugin);
        flushTask = Bukkit.getScheduler().runTaskTimer(plugin, worldEdit::tick,
                FLUSH_PERIOD_TICKS, FLUSH_PERIOD_TICKS);
    }

    /**
     * One committed record.
     *
     * <p>MONITOR, because this must not influence anything, and {@code ignoreCancelled}
     * is irrelevant: {@link RecordCommittedEvent} is not cancellable — by the time it
     * fires the record is already on Spyglass's durable pipeline.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onRecordCommitted(RecordCommittedEvent event) {
        EventRecord record = event.record();
        if (record == null) {
            return;
        }
        if (WorldEditAggregator.isWorldEdit(record)) {
            // Accumulated, not published: one //set is up to a million records, and
            // publishing them one to one is the volume mistake §6.2 forbids.
            worldEdit.accumulate(record, System.currentTimeMillis());
            return;
        }
        publisher.publish(record);
    }

    @Override
    public void close() {
        if (flushTask != null) {
            flushTask.cancel();
            flushTask = null;
        }
        HandlerList.unregisterAll(this);
        HandlerList.unregisterAll(explosions);
        // A WorldEdit operation in flight at shutdown still happened, and the SDK's own
        // close drains what it is holding — so this event has somewhere to go.
        worldEdit.flushAll();
        plugin.getLogger().info("Crusalis platform integration stopped after "
                + publisher.published() + " events published, " + publisher.dropped()
                + " dropped by the ring.");
    }

    /** Visible for the tests. */
    PlatformPublisher publisherForTest() {
        return publisher;
    }
}
