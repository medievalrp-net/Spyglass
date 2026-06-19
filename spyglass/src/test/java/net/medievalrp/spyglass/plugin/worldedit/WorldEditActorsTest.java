package net.medievalrp.spyglass.plugin.worldedit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sk89q.worldedit.extension.platform.Actor;
import java.util.UUID;
import net.medievalrp.spyglass.api.event.Source;
import org.junit.jupiter.api.Test;

/**
 * The WorldEdit actor -> Spyglass source mapping (#105). A player edit carries
 * the player's identity; every non-player actor (console / RCON / command block
 * / plugin API) is attributed to the console so the edit is still recorded.
 */
class WorldEditActorsTest {

    @Test
    void playerActorResolvesToPlayerSource() {
        UUID id = UUID.randomUUID();
        Actor actor = mock(Actor.class);
        when(actor.isPlayer()).thenReturn(true);
        when(actor.getUniqueId()).thenReturn(id);
        when(actor.getName()).thenReturn("Builder");

        Source source = WorldEditActors.resolveSource(actor);

        assertThat(source.kind()).isEqualTo(Source.PLAYER);
        assertThat(source.playerId()).isEqualTo(id);
        assertThat(source.playerName()).isEqualTo("Builder");
    }

    @Test
    void nonPlayerActorResolvesToConsoleSource() {
        Actor actor = mock(Actor.class);
        when(actor.isPlayer()).thenReturn(false);

        Source source = WorldEditActors.resolveSource(actor);

        assertThat(source).isEqualTo(Source.console());
    }

    @Test
    void nullActorResolvesToConsoleSource() {
        // An API edit with no actor still gets logged, attributed to console.
        assertThat(WorldEditActors.resolveSource(null)).isEqualTo(Source.console());
    }
}
