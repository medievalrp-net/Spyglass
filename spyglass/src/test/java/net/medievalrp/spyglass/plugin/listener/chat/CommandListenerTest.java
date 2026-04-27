package net.medievalrp.spyglass.plugin.listener.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.medievalrp.spyglass.api.event.CommandRecord;
import net.medievalrp.spyglass.api.event.EventRecord;
import net.medievalrp.spyglass.api.util.Duration;
import net.medievalrp.spyglass.plugin.listener.RecordingSupport;
import net.medievalrp.spyglass.plugin.pipeline.AsyncRecorder;
import net.medievalrp.spyglass.plugin.pipeline.Recorder;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CommandListenerTest {

    private static final UUID PLAYER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID WORLD_ID = UUID.fromString("77777777-7777-7777-7777-777777777777");

    private CapturingRecorder recorder;
    private CommandListener listener;

    @BeforeEach
    void setUp() {
        recorder = new CapturingRecorder();
        RecordingSupport support = new RecordingSupport(new Duration(3600));
        listener = new CommandListener(recorder, support);
    }

    @Test
    void declaresCommandEventName() {
        assertThat(listener.events()).isEqualTo(Set.of("command"));
    }

    @Test
    void recordsCommandHeadAndFullLineForPlayerCommand() {
        Player alice = mockPlayer();
        PlayerCommandPreprocessEvent event = mock(PlayerCommandPreprocessEvent.class);
        when(event.getPlayer()).thenReturn(alice);
        when(event.getMessage()).thenReturn("/give Alice diamond 64");

        listener.onCommand(event);

        assertThat(recorder.records).hasSize(1);
        CommandRecord record = (CommandRecord) recorder.records.get(0);
        assertThat(record.target()).isEqualTo("give");
        assertThat(record.commandLine()).isEqualTo("/give Alice diamond 64");
        assertThat(record.source().playerId()).isEqualTo(PLAYER_ID);
    }

    @Test
    void slashlessChatTreatedAsCommandHeadIsFirstWord() {
        // Some plugins fire PlayerCommandPreprocessEvent for non-slash
        // shortcuts; the head extractor must still produce something
        // sensible.
        Player alice = mockPlayer();
        PlayerCommandPreprocessEvent event = mock(PlayerCommandPreprocessEvent.class);
        when(event.getPlayer()).thenReturn(alice);
        when(event.getMessage()).thenReturn("emote wave");

        listener.onCommand(event);

        CommandRecord record = (CommandRecord) recorder.records.get(0);
        assertThat(record.target()).isEqualTo("emote");
        assertThat(record.commandLine()).isEqualTo("emote wave");
    }

    @Test
    void truncatesAbusivelyLongCommandLine() {
        Player alice = mockPlayer();
        PlayerCommandPreprocessEvent event = mock(PlayerCommandPreprocessEvent.class);
        when(event.getPlayer()).thenReturn(alice);
        String giant = "/say " + "x".repeat(RecordingSupport.MAX_TEXT_LEN);
        when(event.getMessage()).thenReturn(giant);

        listener.onCommand(event);

        CommandRecord record = (CommandRecord) recorder.records.get(0);
        assertThat(record.commandLine().length()).isEqualTo(RecordingSupport.MAX_TEXT_LEN);
    }

    private static Player mockPlayer() {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(PLAYER_ID);
        when(player.getName()).thenReturn("Alice");
        World world = mock(World.class);
        when(world.getUID()).thenReturn(WORLD_ID);
        when(world.getName()).thenReturn("world");
        Location location = new Location(world, 5, 64, 5);
        when(player.getLocation()).thenReturn(location);
        return player;
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
