package net.medievalrp.spyglass.plugin.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;
import net.medievalrp.spyglass.api.util.BlockLocation;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.junit.jupiter.api.Test;

/**
 * #116: the allocation-free {@link BlockLocations#fromBlock(Block)} and
 * {@link BlockLocations#from(World, int, int, int)} overloads must produce
 * exactly the {@link BlockLocation} value that
 * {@code fromLocation(block.getLocation())} would, without going through a
 * throwaway {@link Location}.
 */
class BlockLocationsTest {

    private static World mockWorld(UUID id, String name) {
        World world = mock(World.class);
        when(world.getUID()).thenReturn(id);
        when(world.getName()).thenReturn(name);
        return world;
    }

    @Test
    void fromBlockEqualsFromLocationForTheSameBlock() {
        UUID worldId = UUID.randomUUID();
        World world = mockWorld(worldId, "world");

        Block block = mock(Block.class);
        when(block.getWorld()).thenReturn(world);
        when(block.getX()).thenReturn(10);
        when(block.getY()).thenReturn(64);
        when(block.getZ()).thenReturn(-7);

        // The Location fromLocation would have read off the block.
        Location location = mock(Location.class);
        when(location.getWorld()).thenReturn(world);
        when(location.getBlockX()).thenReturn(10);
        when(location.getBlockY()).thenReturn(64);
        when(location.getBlockZ()).thenReturn(-7);

        BlockLocation expected = BlockLocations.fromLocation(location);
        BlockLocation actual = BlockLocations.fromBlock(block);

        assertThat(actual)
                .as("fromBlock must equal fromLocation(block.getLocation())")
                .isEqualTo(expected)
                .isEqualTo(new BlockLocation(worldId, "world", 10, 64, -7));
        // The whole point: no throwaway Location is materialized.
        verify(block, never()).getLocation();
    }

    @Test
    void fromWorldCoordsEqualsDirectConstruction() {
        UUID worldId = UUID.randomUUID();
        World world = mockWorld(worldId, "nether");

        BlockLocation actual = BlockLocations.from(world, -1, 5, 2048);

        assertThat(actual).isEqualTo(new BlockLocation(worldId, "nether", -1, 5, 2048));
    }

    @Test
    void fromBlockAndFromWorldCoordsAgree() {
        UUID worldId = UUID.randomUUID();
        World world = mockWorld(worldId, "world");

        Block block = mock(Block.class);
        when(block.getWorld()).thenReturn(world);
        when(block.getX()).thenReturn(3);
        when(block.getY()).thenReturn(7);
        when(block.getZ()).thenReturn(11);

        assertThat(BlockLocations.fromBlock(block))
                .isEqualTo(BlockLocations.from(world, 3, 7, 11));
    }

    // ===== #232: null world (entity observed mid world-unload) =========
    // The factories must yield the worldless sentinel instead of NPEing on
    // the tick - and must NOT return null, which would ripple a null
    // location into the record (the #230 drain-poison shape).

    @Test
    void nullWorldLocationYieldsWorldlessSentinelNotThrow() {
        Location stale = mock(Location.class);
        when(stale.getWorld()).thenReturn(null);
        when(stale.getBlockX()).thenReturn(17);
        when(stale.getBlockY()).thenReturn(65);
        when(stale.getBlockZ()).thenReturn(-4);

        BlockLocation loc = BlockLocations.fromLocation(stale);

        assertThat(loc).isNotNull();
        assertThat(loc.worldId()).isEqualTo(new UUID(0L, 0L));
        assertThat(loc.worldName()).isEmpty();
        assertThat(loc.x()).isEqualTo(17);
        assertThat(loc.y()).isEqualTo(65);
        assertThat(loc.z()).isEqualTo(-4);
    }

    @Test
    void nullWorldCoordsAndBlockYieldTheSameSentinel() {
        BlockLocation viaCoords = BlockLocations.from(null, 1, 2, 3);
        assertThat(viaCoords).isNotNull();
        assertThat(viaCoords.worldId()).isEqualTo(new UUID(0L, 0L));

        Block orphan = mock(Block.class);
        when(orphan.getWorld()).thenReturn(null);
        when(orphan.getX()).thenReturn(1);
        when(orphan.getY()).thenReturn(2);
        when(orphan.getZ()).thenReturn(3);
        assertThat(BlockLocations.fromBlock(orphan)).isEqualTo(viaCoords);
    }
}
