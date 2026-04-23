package net.medievalrp.spyglass.plugin.listener.player;

import java.time.Instant;
import java.util.stream.Stream;
import net.medievalrp.spyglass.api.event.TeleportRecord;
import net.medievalrp.spyglass.api.extension.EventExtractor;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.plugin.listener.ExtractorSupport;
import net.medievalrp.spyglass.plugin.util.BlockLocations;
import org.bukkit.event.player.PlayerTeleportEvent;

public final class TeleportExtractor implements EventExtractor<PlayerTeleportEvent, TeleportRecord> {

    private final ExtractorSupport support;

    public TeleportExtractor(ExtractorSupport support) {
        this.support = support;
    }

    @Override
    public Class<PlayerTeleportEvent> eventType() {
        return PlayerTeleportEvent.class;
    }

    @Override
    public Stream<TeleportRecord> extract(PlayerTeleportEvent event) {
        if (event.getTo() == null || event.getFrom() == null) {
            return Stream.empty();
        }
        PlayerTeleportEvent.TeleportCause cause = event.getCause();
        if (cause == PlayerTeleportEvent.TeleportCause.UNKNOWN) {
            return Stream.empty();
        }
        BlockLocation from = BlockLocations.fromLocation(event.getFrom());
        BlockLocation to = BlockLocations.fromLocation(event.getTo());
        Instant occurred = support.now();
        return Stream.of(new TeleportRecord(
                support.newId(),
                1,
                "teleport",
                occurred,
                support.expiresAt(occurred),
                support.playerOrigin(),
                support.playerSource(event.getPlayer()),
                from,
                event.getPlayer().getName(),
                from,
                to,
                cause.name()));
    }
}
