package net.medievalrp.omniscience2.plugin.listener.player;

import java.util.stream.Stream;
import net.medievalrp.omniscience2.api.event.JoinRecord;
import net.medievalrp.omniscience2.api.event.RecordContext;
import net.medievalrp.omniscience2.api.extension.EventExtractor;
import net.medievalrp.omniscience2.api.util.BlockLocation;
import net.medievalrp.omniscience2.plugin.listener.ExtractorSupport;
import net.medievalrp.omniscience2.plugin.util.BlockLocations;
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
        String address = event.getPlayer().getAddress() == null
                ? ""
                : event.getPlayer().getAddress().getAddress().getHostAddress();
        RecordContext ctx = support.playerContext(event.getPlayer(), location);
        return Stream.of(JoinRecord.of(ctx, event.getPlayer().getName(), address));
    }
}
