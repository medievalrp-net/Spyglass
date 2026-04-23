package net.medievalrp.spyglass.plugin.listener.player;

import java.time.Instant;
import java.util.stream.Stream;
import net.medievalrp.spyglass.api.event.JoinRecord;
import net.medievalrp.spyglass.api.extension.EventExtractor;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.plugin.listener.ExtractorSupport;
import net.medievalrp.spyglass.plugin.util.BlockLocations;
import org.bukkit.event.player.PlayerJoinEvent;

public final class JoinExtractor implements EventExtractor<PlayerJoinEvent, JoinRecord> {

    private final ExtractorSupport support;

    public JoinExtractor(ExtractorSupport support) {
        this.support = support;
    }

    @Override
    public Class<PlayerJoinEvent> eventType() {
        return PlayerJoinEvent.class;
    }

    @Override
    public Stream<JoinRecord> extract(PlayerJoinEvent event) {
        BlockLocation location = BlockLocations.fromLocation(event.getPlayer().getLocation());
        Instant occurred = support.now();
        String address = event.getPlayer().getAddress() == null
                ? ""
                : event.getPlayer().getAddress().getAddress().getHostAddress();
        return Stream.of(new JoinRecord(
                support.newId(),
                1,
                "join",
                occurred,
                support.expiresAt(occurred),
                support.playerOrigin(),
                support.playerSource(event.getPlayer()),
                location,
                event.getPlayer().getName(),
                address));
    }
}
