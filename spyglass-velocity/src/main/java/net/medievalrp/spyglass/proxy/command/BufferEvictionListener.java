package net.medievalrp.spyglass.proxy.command;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;

/**
 * Evicts the search {@link SpyglassCommand.TimestampedBuffer} for a player
 * when they disconnect from the proxy. Without this, every unique player who
 * runs a result-returning /sgv search leaks a buffer entry until the proxy
 * restarts (proxy instances are long-lived and see every player on the
 * network).
 *
 * <p>Registered via the Velocity event manager in {@code SpyglassProxy}.
 * The command itself is a {@link com.velocitypowered.api.command.SimpleCommand}
 * and cannot carry {@code @Subscribe} annotations, so eviction lives here.
 */
public final class BufferEvictionListener {

    private final SpyglassCommand command;

    public BufferEvictionListener(SpyglassCommand command) {
        this.command = command;
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        command.evict(event.getPlayer().getUniqueId());
    }
}
