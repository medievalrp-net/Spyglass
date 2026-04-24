package net.medievalrp.spyglass.plugin.listener.chat;

import java.util.Set;
import java.util.stream.Stream;
import net.medievalrp.spyglass.api.event.CommandRecord;
import net.medievalrp.spyglass.api.event.RecordContext;
import net.medievalrp.spyglass.api.extension.EventExtractor;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.plugin.listener.ExtractorSupport;
import net.medievalrp.spyglass.plugin.util.BlockLocations;
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
    public Set<String> events() {
        return Set.of("command");
    }

    @Override
    public Stream<CommandRecord> extract(PlayerCommandPreprocessEvent event) {
        String line = event.getMessage();
        BlockLocation location = BlockLocations.fromLocation(event.getPlayer().getLocation());
        RecordContext ctx = support.playerContext(event.getPlayer(), location);
        String head = extractHead(line);
        return Stream.of(CommandRecord.of(ctx, head, line));
    }

    private static String extractHead(String line) {
        String trimmed = line.startsWith("/") ? line.substring(1) : line;
        int space = trimmed.indexOf(' ');
        return space < 0 ? trimmed : trimmed.substring(0, space);
    }
}
