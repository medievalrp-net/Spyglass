package net.medievalrp.omniscience2.plugin.listener.player;

import java.util.stream.Stream;
import net.medievalrp.omniscience2.api.event.RecordContext;
import net.medievalrp.omniscience2.api.event.TeleportRecord;
import net.medievalrp.omniscience2.api.extension.EventExtractor;
import net.medievalrp.omniscience2.api.util.BlockLocation;
import net.medievalrp.omniscience2.plugin.listener.ExtractorSupport;
import net.medievalrp.omniscience2.plugin.util.BlockLocations;
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
        RecordContext ctx = support.playerContext(event.getPlayer(), from);
        return Stream.of(TeleportRecord.of(ctx, event.getPlayer().getName(), from, to, cause.name()));
    }
}
