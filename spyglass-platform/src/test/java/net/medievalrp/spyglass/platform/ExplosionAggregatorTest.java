package net.medievalrp.spyglass.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.EntityType;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.junit.jupiter.api.Test;

class ExplosionAggregatorTest {

    private final FakePlatform platform = new FakePlatform();
    private final PlatformPublisher publisher = new PlatformPublisher(platform,
            new PlatformSettings(true, Set.of("*"), Set.of(), Set.of(), Set.of(), true),
            message -> { });
    private final ExplosionAggregator aggregator = new ExplosionAggregator(publisher);

    private final World world = mock(World.class);

    private Block blockAt(int x, int y, int z) {
        Block block = mock(Block.class);
        when(block.getX()).thenReturn(x);
        when(block.getY()).thenReturn(y);
        when(block.getZ()).thenReturn(z);
        return block;
    }

    @Test
    void oneCreeperProducesOneEventWithTheBlockCountAndRadius() {
        when(world.getName()).thenReturn("world");
        Location epicentre = new Location(world, 100, 64, -200);

        List<Block> destroyed = new ArrayList<>();
        destroyed.add(blockAt(100, 64, -200));
        destroyed.add(blockAt(102, 65, -200));
        destroyed.add(blockAt(97, 63, -203));   // the furthest: 3 on x and z
        Creeper creeper = mock(Creeper.class);
        when(creeper.getType()).thenReturn(EntityType.CREEPER);

        aggregator.onEntityExplode(new EntityExplodeEvent(creeper, epicentre, destroyed, 0.3f,
                org.bukkit.ExplosionResult.DESTROY));

        assertThat(platform.published).hasSize(1);
        FakePlatform.Published event = platform.published.get(0);
        assertThat(event.type()).isEqualTo("spyglass.explosion");
        // No actor: Bukkit does not say who primed it, and an entity id in the uuid
        // field would read as an account.
        assertThat(event.uuid()).isNull();
        assertThat(event.json())
                .contains("\"world\":\"world\",\"x\":100,\"y\":64,\"z\":-200")
                .contains("\"cause\":\"#creeper\"")
                // Blocks destroyed, so a sum over the column is blocks, not explosions.
                .contains("\"amount\":3")
                .contains("\"radius\":3");
    }

    @Test
    void anExplosionThatDestroyedNothingIsStillOneEvent() {
        when(world.getName()).thenReturn("world");
        Creeper creeper = mock(Creeper.class);
        when(creeper.getType()).thenReturn(EntityType.CREEPER);

        aggregator.onEntityExplode(new EntityExplodeEvent(creeper,
                new Location(world, 1, 2, 3), new ArrayList<>(), 1.0f,
                org.bukkit.ExplosionResult.KEEP));

        // It happened, and "a creeper went off and took nothing" is a different fact
        // from silence — not least for a server arguing about block protection.
        assertThat(platform.published).hasSize(1);
        assertThat(platform.published.get(0).json())
                .contains("\"amount\":0")
                .contains("\"radius\":0");
    }

    @Test
    void aDisabledExplosionTypePublishesNothing() {
        platform.disabled.add("spyglass.explosion");
        when(world.getName()).thenReturn("world");
        Creeper creeper = mock(Creeper.class);
        when(creeper.getType()).thenReturn(EntityType.CREEPER);

        aggregator.onEntityExplode(new EntityExplodeEvent(creeper,
                new Location(world, 1, 2, 3), List.of(blockAt(1, 2, 3)), 1.0f,
                org.bukkit.ExplosionResult.DESTROY));

        assertThat(platform.published).isEmpty();
    }

    /** Material is referenced so the mock of a block type below stays honest. */
    @Test
    void theMaterialEnumIsUsableWithoutAServer() {
        assertThat(Material.TNT.name()).isEqualTo("TNT");
    }
}
