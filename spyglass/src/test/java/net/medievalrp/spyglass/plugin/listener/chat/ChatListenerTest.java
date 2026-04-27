package net.medievalrp.spyglass.plugin.listener.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.medievalrp.spyglass.api.event.ChatRecord;
import net.medievalrp.spyglass.api.event.EventRecord;
import net.medievalrp.spyglass.api.util.Duration;
import net.medievalrp.spyglass.plugin.listener.RecordingSupport;
import net.medievalrp.spyglass.plugin.pipeline.AsyncRecorder;
import net.medievalrp.spyglass.plugin.pipeline.Recorder;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ChatListenerTest {

    private static final UUID ALICE = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID BOB = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID WORLD_ID = UUID.fromString("77777777-7777-7777-7777-777777777777");

    private CapturingRecorder recorder;
    private RecordingSupport support;
    private ChatListener listener;

    @BeforeEach
    void setUp() {
        recorder = new CapturingRecorder();
        support = new RecordingSupport(new Duration(3600));
        listener = new ChatListener(recorder, support);
    }

    @Test
    void declaresSayEventName() {
        assertThat(listener.events()).isEqualTo(Set.of("say"));
    }

    @Test
    void emitsChatRecordWithMessageAndRecipients() {
        Player alice = mockPlayer(ALICE, "Alice");
        Player bob = mockPlayer(BOB, "Bob");
        AsyncChatEvent event = mockChat(alice, "hello world", List.of(alice, bob));

        listener.onAsyncChat(event);

        assertThat(recorder.records).hasSize(1);
        ChatRecord record = (ChatRecord) recorder.records.get(0);
        assertThat(record.message()).isEqualTo("hello world");
        assertThat(record.recipients()).containsExactlyInAnyOrder(ALICE, BOB);
        assertThat(record.source().playerId()).isEqualTo(ALICE);
        assertThat(record.source().playerName()).isEqualTo("Alice");
    }

    @Test
    void fallsBackToSelfWhenNoOtherViewers() {
        Player alice = mockPlayer(ALICE, "Alice");
        AsyncChatEvent event = mockChat(alice, "anyone there?", List.of());

        listener.onAsyncChat(event);

        ChatRecord record = (ChatRecord) recorder.records.get(0);
        assertThat(record.recipients())
                .as("empty viewer set falls back to speaker so 'who heard this' stays answerable")
                .containsExactly(ALICE);
    }

    @Test
    void truncatesMegabyteMessageToHardCap() {
        // A modded client could ship arbitrary-length text; safeText
        // caps at MAX_TEXT_LEN to keep one rogue packet from bloating
        // a single record by ~1 MB.
        String giant = "x".repeat(RecordingSupport.MAX_TEXT_LEN + 5_000);
        Player alice = mockPlayer(ALICE, "Alice");
        AsyncChatEvent event = mockChat(alice, giant, List.of(alice));

        listener.onAsyncChat(event);

        ChatRecord record = (ChatRecord) recorder.records.get(0);
        assertThat(record.message().length()).isEqualTo(RecordingSupport.MAX_TEXT_LEN);
    }

    private static Player mockPlayer(UUID id, String name) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        when(player.getName()).thenReturn(name);
        World world = mock(World.class);
        when(world.getUID()).thenReturn(WORLD_ID);
        when(world.getName()).thenReturn("world");
        Location location = new Location(world, 10, 64, 20);
        when(player.getLocation()).thenReturn(location);
        return player;
    }

    private static AsyncChatEvent mockChat(Player sender, String message, List<Player> viewers) {
        AsyncChatEvent event = mock(AsyncChatEvent.class);
        when(event.getPlayer()).thenReturn(sender);
        when(event.message()).thenReturn(Component.text(message));
        // viewers() returns Set<Audience>; the listener filters to Player instances.
        when(event.viewers()).thenReturn(new java.util.LinkedHashSet<>(viewers));
        return event;
    }

    private static final class CapturingRecorder implements Recorder {
        final List<EventRecord> records = new java.util.ArrayList<>();

        @Override
        public void record(EventRecord record) {
            records.add(record);
        }

        @Override
        public AsyncRecorder.ShutdownReport shutdown(Duration timeout) {
            return new AsyncRecorder.ShutdownReport(records.size(), 0, 0);
        }
    }
}
