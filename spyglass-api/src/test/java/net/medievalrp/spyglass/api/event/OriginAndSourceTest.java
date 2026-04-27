package net.medievalrp.spyglass.api.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import net.medievalrp.spyglass.api.util.BlockLocation;
import org.junit.jupiter.api.Test;

class OriginAndSourceTest {

    @Test
    void originFactoriesPopulateKindAndDetail() {
        assertThat(Origin.player()).returns(Origin.PLAYER, Origin::kind).returns(null, Origin::detail);
        assertThat(Origin.worldEdit()).returns(Origin.WORLDEDIT, Origin::kind);
        assertThat(Origin.fawe()).returns(Origin.FAWE, Origin::kind);
        assertThat(Origin.plugin("Reserv"))
                .returns(Origin.PLUGIN, Origin::kind)
                .returns("Reserv", Origin::detail);
        assertThat(Origin.environment("fire-spread"))
                .returns(Origin.ENVIRONMENT, Origin::kind)
                .returns("fire-spread", Origin::detail);
    }

    @Test
    void sourceFactoriesDispatchByKind() {
        UUID id = UUID.randomUUID();
        assertThat(Source.player(id, "Alice"))
                .returns(Source.PLAYER, Source::kind)
                .returns(id, Source::playerId)
                .returns("Alice", Source::playerName);
        assertThat(Source.entity(id, "creeper"))
                .returns(Source.ENTITY, Source::kind)
                .returns(id, Source::entityId)
                .returns("creeper", Source::entityType);
        assertThat(Source.plugin("Reserv"))
                .returns(Source.PLUGIN, Source::kind)
                .returns("Reserv", Source::pluginName);
        assertThat(Source.console()).returns(Source.CONSOLE, Source::kind);
        assertThat(Source.environment("fire"))
                .returns(Source.ENVIRONMENT, Source::kind)
                .returns("fire", Source::description);

        BlockLocation loc = new BlockLocation(UUID.randomUUID(), "world", 1, 2, 3);
        assertThat(Source.commandBlock(loc))
                .returns(Source.COMMAND_BLOCK, Source::kind)
                .returns(loc, Source::commandBlockLocation);
    }

    @Test
    void sourceDisplayNameResolvesByKind() {
        UUID id = UUID.randomUUID();
        assertThat(Source.player(id, "Alice").displayName()).isEqualTo("Alice");
        assertThat(Source.player(id, null).displayName()).isEqualTo("unknown-player");
        assertThat(Source.entity(id, "creeper").displayName()).isEqualTo("creeper");
        assertThat(Source.entity(id, null).displayName()).isEqualTo("unknown-entity");
        assertThat(Source.plugin("Reserv").displayName()).isEqualTo("Reserv");
        assertThat(Source.console().displayName()).isEqualTo("console");
        assertThat(Source.environment("fire").displayName()).isEqualTo("fire");
        assertThat(Source.environment(null).displayName()).isEqualTo("environment");
    }
}
