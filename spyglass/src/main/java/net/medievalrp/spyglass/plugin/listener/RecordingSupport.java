package net.medievalrp.spyglass.plugin.listener;

import java.time.Instant;
import java.util.UUID;
import net.medievalrp.spyglass.api.event.Origin;
import net.medievalrp.spyglass.api.event.RecordContext;
import net.medievalrp.spyglass.api.event.Source;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.api.util.Duration;
import org.bukkit.entity.Player;

public final class RecordingSupport {

    private final Duration retention;

    public RecordingSupport(Duration retention) {
        this.retention = retention;
    }

    public Instant now() {
        return Instant.now();
    }

    public Instant expiresAt(Instant occurred) {
        return retention.after(occurred);
    }

    public Source playerSource(Player player) {
        return Source.player(player.getUniqueId(), player.getName());
    }

    public Origin playerOrigin() {
        return Origin.player();
    }

    public Source environmentSource(String description) {
        return Source.environment(description);
    }

    public Origin environmentOrigin(String description) {
        return Origin.environment(description);
    }

    public Source entitySource(java.util.UUID entityId, String entityType) {
        return Source.entity(entityId, entityType);
    }

    public UUID newId() {
        return UUID.randomUUID();
    }

    /**
     * One-shot builder for the 7-field {@link RecordContext} header shared by
     * every record. Callers hand the result to a record's static {@code of()}
     * factory along with the type-specific fields.
     */
    public RecordContext context(Origin origin, Source source, BlockLocation location) {
        return context(Instant.now(), origin, source, location);
    }

    /**
     * Same as {@link #context(Origin, Source, BlockLocation)} but with an
     * explicit {@code occurred} timestamp — so a listener iterating blocks
     * from one event can share a single wall-clock across every record it
     * emits, even though each record gets its own UUID.
     */
    public RecordContext context(Instant occurred, Origin origin, Source source, BlockLocation location) {
        return RecordContext.fresh(occurred, retention.after(occurred), origin, source, location);
    }

    /** Shortcut: player-origin, player-source context for a located event. */
    public RecordContext playerContext(Player player, BlockLocation location) {
        return context(playerOrigin(), playerSource(player), location);
    }

    /** Shortcut: environment-origin, environment-source context. */
    public RecordContext environmentContext(String description, BlockLocation location) {
        return context(environmentOrigin(description), environmentSource(description), location);
    }
}
