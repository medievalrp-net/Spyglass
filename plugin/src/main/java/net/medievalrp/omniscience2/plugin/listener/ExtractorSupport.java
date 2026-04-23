package net.medievalrp.omniscience2.plugin.listener;

import java.time.Instant;
import java.util.UUID;
import net.medievalrp.omniscience2.api.event.Origin;
import net.medievalrp.omniscience2.api.event.Source;
import net.medievalrp.omniscience2.api.util.Duration;
import org.bukkit.entity.Player;

public final class ExtractorSupport {

    private final Duration retention;

    public ExtractorSupport(Duration retention) {
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

    public UUID newId() {
        return UUID.randomUUID();
    }
}
