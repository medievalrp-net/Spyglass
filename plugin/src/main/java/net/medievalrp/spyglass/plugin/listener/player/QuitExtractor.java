package net.medievalrp.spyglass.plugin.listener.player;

import java.util.stream.Stream;
import net.medievalrp.spyglass.api.event.QuitRecord;
import net.medievalrp.spyglass.api.event.RecordContext;
import net.medievalrp.spyglass.api.extension.EventExtractor;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.plugin.listener.ExtractorSupport;
import net.medievalrp.spyglass.plugin.util.BlockLocations;
import org.bukkit.event.player.PlayerQuitEvent;

public final class QuitExtractor implements EventExtractor<PlayerQuitEvent, QuitRecord> {

    private final ExtractorSupport support;

    public QuitExtractor(ExtractorSupport support) {
        this.support = support;
    }

    @Override
    public Class<PlayerQuitEvent> eventType() {
        return PlayerQuitEvent.class;
    }

    @Override
    public Stream<QuitRecord> extract(PlayerQuitEvent event) {
        BlockLocation location = BlockLocations.fromLocation(event.getPlayer().getLocation());
        RecordContext ctx = support.playerContext(event.getPlayer(), location);
        return Stream.of(QuitRecord.of(ctx, event.getPlayer().getName()));
    }
}
