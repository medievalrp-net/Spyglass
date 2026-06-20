package net.medievalrp.spyglass.plugin.util;

import static org.assertj.core.api.Assertions.assertThat;

import net.medievalrp.spyglass.api.event.BlockSnapshot;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

/**
 * #116: the "broken to air" after-snapshot is the same immutable value on
 * every break, so {@link BlockSnapshots#air()} returns a shared constant
 * instead of allocating per record. Sharing is safe because
 * {@link BlockSnapshot} is an immutable record and nothing identity-checks it.
 */
class BlockSnapshotsTest {

    @Test
    void airReturnsTheSameSharedInstance() {
        assertThat(BlockSnapshots.air())
                .as("air() must hand back one shared instance, not a fresh allocation")
                .isSameAs(BlockSnapshots.air());
    }

    @Test
    void airHasTheExpectedValue() {
        BlockSnapshot air = BlockSnapshots.air();
        assertThat(air.material()).isEqualTo(Material.AIR);
        assertThat(air.blockData()).isEqualTo("minecraft:air");
        assertThat(air.isAir()).isTrue();
        assertThat(air.simple())
                .as("an air snapshot carries no tile-entity payload")
                .isTrue();
        assertThat(air.containerItems()).isEmpty();
        assertThat(air.signFront()).isEmpty();
        assertThat(air.signBack()).isEmpty();
        assertThat(air.bannerPatterns()).isEmpty();
        assertThat(air.potSherds()).isEmpty();
        assertThat(air.jukeboxRecord()).isNull();
    }
}
