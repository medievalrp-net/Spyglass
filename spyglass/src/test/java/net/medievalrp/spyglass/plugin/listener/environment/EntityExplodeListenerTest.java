package net.medievalrp.spyglass.plugin.listener.environment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;
import net.medievalrp.spyglass.api.event.Source;
import net.medievalrp.spyglass.api.util.Duration;
import net.medievalrp.spyglass.plugin.listener.RecordingSupport;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.junit.jupiter.api.Test;

/**
 * Attribution policy for explosion grief (#34): player-lit TNT carries
 * the igniter as the source - so {@code p:<griefer>} searches and
 * rollbacks cover the crater - while chained/dispenser TNT and mob
 * explosions stay entity-attributed for {@code c:} sweeps.
 */
class EntityExplodeListenerTest {

    private final RecordingSupport support = new RecordingSupport(Duration.parse("4w"), "test");
    private final EntityExplodeListener listener = new EntityExplodeListener(
            mock(net.medievalrp.spyglass.plugin.pipeline.Recorder.class), support, Runnable::run);

    @Test
    void playerLitTntAttributesTheIgniter() {
        UUID igniterId = UUID.randomUUID();
        Player igniter = mock(Player.class);
        when(igniter.getUniqueId()).thenReturn(igniterId);
        when(igniter.getName()).thenReturn("Griefer");
        TNTPrimed tnt = mock(TNTPrimed.class);
        when(tnt.getSource()).thenReturn(igniter);

        Source source = listener.explosionSource(tnt, "tnt");

        assertThat(source.playerId()).isEqualTo(igniterId);
        assertThat(source.playerName()).isEqualTo("Griefer");
    }

    @Test
    void unattributedTntStaysEntitySourced() {
        TNTPrimed tnt = mock(TNTPrimed.class);
        when(tnt.getSource()).thenReturn(null);
        UUID entityId = UUID.randomUUID();
        when(tnt.getUniqueId()).thenReturn(entityId);

        Source source = listener.explosionSource(tnt, "tnt");

        assertThat(source.playerId()).isNull();
        assertThat(source.entityType()).isEqualTo("tnt");
    }

    @Test
    void mobExplosionsStayEntitySourced() {
        Creeper creeper = mock(Creeper.class);
        when(creeper.getUniqueId()).thenReturn(UUID.randomUUID());

        Source source = listener.explosionSource(creeper, "creeper");

        assertThat(source.playerId()).isNull();
        assertThat(source.entityType()).isEqualTo("creeper");
    }
}
