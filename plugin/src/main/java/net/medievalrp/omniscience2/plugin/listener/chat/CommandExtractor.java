package net.medievalrp.omniscience2.plugin.listener.chat;

import java.time.Instant;
import java.util.stream.Stream;
import net.medievalrp.omniscience2.api.event.CommandRecord;
import net.medievalrp.omniscience2.api.extension.EventExtractor;
import net.medievalrp.omniscience2.api.util.BlockLocation;
import net.medievalrp.omniscience2.plugin.listener.ExtractorSupport;
import net.medievalrp.omniscience2.plugin.util.BlockLocations;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

public final class CommandExtractor implements EventExtractor<PlayerCommandPreprocessEvent, CommandRecord> {

    private final ExtractorSupport support;

    public CommandExtractor(ExtractorSupport support) {
        this.support = support;
    }

    @Override
    public Class<PlayerCommandPreprocessEvent> eventType() {
        return PlayerCommandPreprocessEvent.class;
    }

    @Override
    public Stream<CommandRecord> extract(PlayerCommandPreprocessEvent event) {
        String line = event.getMessage();
        BlockLocation location = BlockLocations.fromLocation(event.getPlayer().getLocation());
        Instant occurred = support.now();
        String head = extractHead(line);
        return Stream.of(new CommandRecord(
                support.newId(),
                1,
                "command",
                occurred,
                support.expiresAt(occurred),
                support.playerOrigin(),
                support.playerSource(event.getPlayer()),
                location,
                head,
                line));
    }

    private static String extractHead(String line) {
        String trimmed = line.startsWith("/") ? line.substring(1) : line;
        int space = trimmed.indexOf(' ');
        return space < 0 ? trimmed : trimmed.substring(0, space);
    }
}
