package net.medievalrp.spyglass.plugin.listener.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import io.papermc.paper.event.player.PlayerToggleEntityAgeLockEvent;
import java.util.ArrayList;
import java.util.UUID;
import net.medievalrp.spyglass.api.event.CustomRecord;
import net.medievalrp.spyglass.api.event.EventCatalog;
import net.medievalrp.spyglass.api.util.Duration;
import net.medievalrp.spyglass.plugin.listener.RecordingSupport;
import net.medievalrp.spyglass.plugin.pipeline.Recorder;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import net.medievalrp.spyglass.api.event.EventRecord;

class AgeLockListenerTest {
    @Test void completedToggleCarriesIdentityAndBothStates() {
        for (boolean before : new boolean[] {false, true}) {
            var fixture = new Fixture(before);
            fixture.listener.onToggle(fixture.event);
            verifyNoInteractions(fixture.recorder);
            when(fixture.entity.getAgeLock()).thenReturn(!before);
            fixture.tasks.forEach(Runnable::run);
            var capture = ArgumentCaptor.forClass(EventRecord.class);
            verify(fixture.recorder).record(capture.capture());
            var record = (CustomRecord) capture.getValue();
            assertThat(record.source().playerName()).isEqualTo("tester");
            assertThat(record.extensions()).containsEntry("before", Boolean.toString(before))
                    .containsEntry("after", Boolean.toString(!before))
                    .containsEntry("entity-id", fixture.entity.getUniqueId().toString());
            assertThat(record.location().x()).isEqualTo(2);
            assertThat(EventCatalog.isRollbackable(record.event())).isFalse();
        }
    }

    @Test void cancelledOrUnappliedAttemptDoesNotCreateHistory() {
        for (int mode = 0; mode < 4; mode++) {
            var fixture = new Fixture(false);
            if (mode == 0) when(fixture.event.isCancelled()).thenReturn(true);
            if (mode == 3) when(fixture.event.isAgeLocked()).thenReturn(false);
            fixture.listener.onToggle(fixture.event);
            if (mode == 1) {
                when(fixture.entity.getAgeLock()).thenReturn(true);
                when(fixture.event.isCancelled()).thenReturn(true);
            }
            fixture.tasks.forEach(Runnable::run);
            verifyNoInteractions(fixture.recorder);
        }
    }

    private static class Fixture {
        final Recorder recorder = mock(Recorder.class);
        final ArrayList<Runnable> tasks = new ArrayList<>();
        final Ageable entity = mock(Ageable.class);
        final PlayerToggleEntityAgeLockEvent event = mock(PlayerToggleEntityAgeLockEvent.class);
        final AgeLockListener listener = new AgeLockListener(recorder,
                new RecordingSupport(new Duration(3600), "test"), tasks::add);
        Fixture(boolean before) {
            World world = mock(World.class);
            when(world.getUID()).thenReturn(UUID.randomUUID());
            when(world.getName()).thenReturn("world");
            when(entity.getLocation()).thenReturn(new Location(world, 2, 70, 3));
            when(entity.getUniqueId()).thenReturn(UUID.randomUUID());
            when(entity.getType()).thenReturn(EntityType.COW);
            when(entity.getAgeLock()).thenReturn(before);
            when(entity.isValid()).thenReturn(true);
            Player player = mock(Player.class);
            when(player.getUniqueId()).thenReturn(UUID.randomUUID());
            when(player.getName()).thenReturn("tester");
            when(event.getPlayer()).thenReturn(player);
            when(event.getEntity()).thenReturn(entity);
            when(event.isAgeLocked()).thenReturn(!before);
        }
    }
}
