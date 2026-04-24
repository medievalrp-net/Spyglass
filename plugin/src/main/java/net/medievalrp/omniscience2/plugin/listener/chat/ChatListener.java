package net.medievalrp.omniscience2.plugin.listener.chat;

import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.medievalrp.omniscience2.api.event.ChatRecord;
import net.medievalrp.omniscience2.api.event.RecordContext;
import net.medievalrp.omniscience2.api.util.BlockLocation;
import net.medievalrp.omniscience2.plugin.listener.RecordingSupport;
import net.medievalrp.omniscience2.plugin.listener.RecordingListener;
import net.medievalrp.omniscience2.plugin.pipeline.Recorder;
import net.medievalrp.omniscience2.plugin.util.BlockLocations;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class ChatListener implements RecordingListener {

    private final Recorder recorder;
    private final RecordingSupport support;

    public ChatListener(Recorder recorder, RecordingSupport support) {
        this.recorder = recorder;
        this.support = support;
    }

    @Override
    public Set<String> events() {
        return Set.of("say");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAsyncChat(AsyncChatEvent event) {
        Player sender = event.getPlayer();
        String message = PlainTextComponentSerializer.plainText().serialize(event.message());
        // Include the speaker in recipients so "who heard this" is always
        // answerable on hover, even for a chat sent to an empty server.
        List<UUID> recipients = event.viewers().stream()
                .filter(Player.class::isInstance)
                .map(Player.class::cast)
                .map(Player::getUniqueId)
                .toList();
        if (recipients.isEmpty()) {
            recipients = List.of(sender.getUniqueId());
        }
        BlockLocation location = BlockLocations.fromLocation(sender.getLocation());
        RecordContext ctx = support.playerContext(sender, location);
        // Target is the message itself so aggregates group by unique content
        // (three "hi"s collapse to one row, "hello" gets its own). Recipients
        // are surfaced on hover.
        recorder.record(ChatRecord.of(ctx, message, message, recipients));
    }
}
