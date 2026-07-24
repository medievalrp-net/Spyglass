package net.medievalrp.spyglass.plugin.snapshot;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.ApiStatus;

/**
 * Bukkit listener for the event-driven capture points {@link PlayerSnapshotService}
 * does not schedule itself - join, quit, death, and world change. The periodic
 * sweep is the service's own repeating task, installed by {@link
 * PlayerSnapshotService#start}, not this listener.
 *
 * <p>A plain {@link Listener}, deliberately NOT a {@code RecordingListener}:
 * this listener is gated by {@code snapshot.players.enabled}
 * ({@code SpyglassConfig}), not by the {@code events} map. The plugin's
 * per-{@code RecordingListener} registration loop only registers a listener
 * whose {@code events()} intersects the enabled-events set - an EMPTY set
 * there means never registered, the opposite of what {@code RecordingListener}'s
 * own javadoc promises for config-agnostic listeners (SpyglassPlugin.java's
 * gating loop). Implementing that interface here would silently wire this
 * listener to the wrong knob. Registration against the real gate is the
 * integration phase's job, not this class's.
 */
@ApiStatus.Internal
public final class PlayerSnapshotListener implements Listener {

    private final PlayerSnapshotService service;
    private final JavaPlugin plugin;

    public PlayerSnapshotListener(PlayerSnapshotService service, JavaPlugin plugin) {
        this.service = service;
        this.plugin = plugin;
    }

    /**
     * One tick after join, not during {@link PlayerJoinEvent} itself: a
     * starter kit or other join-time inventory grant from another plugin
     * isn't guaranteed to have landed yet when this event fires, so capturing
     * immediately risks logging a transient pre-kit state. {@link
     * Player#isOnline()} guards a quit that races the one-tick delay.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                service.capture(player, PlayerSnapshot.CAUSE_JOIN);
            }
        }, 1L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onQuit(PlayerQuitEvent event) {
        service.capture(event.getPlayer(), PlayerSnapshot.CAUSE_QUIT);
    }

    /**
     * Captured during the death event itself, before Bukkit clears the live
     * inventory into the drop list - this is the last point the pre-death
     * contents are readable from {@link Player#getInventory()}.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        service.capture(event.getEntity(), PlayerSnapshot.CAUSE_DEATH);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        service.capture(event.getPlayer(), PlayerSnapshot.CAUSE_WORLD_CHANGE);
    }
}
