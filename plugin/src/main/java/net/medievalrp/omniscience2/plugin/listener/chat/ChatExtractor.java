package net.medievalrp.omniscience2.plugin.listener.chat;

import io.papermc.paper.event.player.AsyncChatEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.medievalrp.omniscience2.api.event.ChatRecord;
import net.medievalrp.omniscience2.api.extension.EventExtractor;
import net.medievalrp.omniscience2.api.util.BlockLocation;
import net.medievalrp.omniscience2.plugin.listener.ExtractorSupport;
import net.medievalrp.omniscience2.plugin.util.BlockLocations;
import org.bukkit.entity.Player;

public final class ChatExtractor implements EventExtractor<AsyncChatEvent, ChatRecord> {

    private final ExtractorSupport support;

    public ChatExtractor(ExtractorSupport support) {
        this.support = support;
    }

    @Override
    public Class<AsyncChatEvent> eventType() {
        return AsyncChatEvent.class;
    }

    @Override
    public Stream<ChatRecord> extract(AsyncChatEvent event) {
        Player sender = event.getPlayer();
        String message = PlainTextComponentSerializer.plainText().serialize(event.message());
        List<UUID> recipients = event.viewers().stream()
                .filter(Player.class::isInstance)
                .map(Player.class::cast)
                .map(Player::getUniqueId)
                .filter(id -> !id.equals(sender.getUniqueId()))
                .toList();
        BlockLocation location = BlockLocations.fromLocation(sender.getLocation());
        Instant occurred = support.now();
        return Stream.of(new ChatRecord(
                support.newId(),
                1,
                "say",
                occurred,
                support.expiresAt(occurred),
                support.playerOrigin(),
                support.playerSource(sender),
                location,
                message,
                message,
                recipients));
    }
}
