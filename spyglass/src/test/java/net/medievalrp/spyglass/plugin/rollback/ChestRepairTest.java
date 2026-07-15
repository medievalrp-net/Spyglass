package net.medievalrp.spyglass.plugin.rollback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Chest;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the #324 double-chest re-pair. A small mocked world lets the
 * pure connectivity logic run without a live server: each cell is a chest (or
 * not) with a facing and a mutable {@link Chest.Type}, and neighbours resolve
 * by coordinate.
 */
class ChestRepairTest {

    private static final class FakeWorld {
        final Map<Long, Block> blocks = new HashMap<>();
        final World world = mock(World.class);

        FakeWorld() {
            when(world.getBlockAt(org.mockito.ArgumentMatchers.anyInt(),
                    org.mockito.ArgumentMatchers.anyInt(),
                    org.mockito.ArgumentMatchers.anyInt()))
                    .thenAnswer(inv -> blocks.get(key(inv.getArgument(0),
                            inv.getArgument(1), inv.getArgument(2))));
        }

        /** A chest cell with the given facing and starting type. */
        void chest(int x, int y, int z, BlockFace facing, Chest.Type type) {
            put(x, y, z, chestBlock(x, y, z, Material.CHEST, facing, type));
        }

        /** A trapped-chest cell (a chest never pairs with one). */
        void trappedChest(int x, int y, int z, BlockFace facing, Chest.Type type) {
            put(x, y, z, chestBlock(x, y, z, Material.TRAPPED_CHEST, facing, type));
        }

        /** A non-chest cell (air) at the given spot. */
        void air(int x, int y, int z) {
            Block block = mock(Block.class);
            when(block.getType()).thenReturn(Material.AIR);
            when(block.getBlockData()).thenReturn(mock(BlockData.class));
            wireNeighbours(block, x, y, z);
            put(x, y, z, block);
        }

        Chest.Type typeAt(int x, int y, int z) {
            return ((Chest) blocks.get(key(x, y, z)).getBlockData()).getType();
        }

        private Block chestBlock(int x, int y, int z, Material material,
                                 BlockFace facing, Chest.Type start) {
            Block block = mock(Block.class);
            Chest.Type[] type = {start};
            when(block.getType()).thenReturn(material);
            // Each read returns a fresh data view over the current type; a
            // write commits the type the caller set on that view.
            when(block.getBlockData()).thenAnswer(inv -> {
                Chest data = mock(Chest.class);
                Chest.Type[] pending = {type[0]};
                when(data.getFacing()).thenReturn(facing);
                when(data.getType()).thenAnswer(i -> pending[0]);
                doAnswer(i -> {
                    pending[0] = i.getArgument(0);
                    return null;
                }).when(data).setType(any());
                return data;
            });
            doAnswer(inv -> {
                type[0] = ((Chest) inv.getArgument(0)).getType();
                return null;
            }).when(block).setBlockData(any(), anyBoolean());
            wireNeighbours(block, x, y, z);
            return block;
        }

        private void wireNeighbours(Block block, int x, int y, int z) {
            when(block.getRelative(any(BlockFace.class))).thenAnswer(inv -> {
                BlockFace face = inv.getArgument(0);
                Block n = blocks.get(key(x + face.getModX(), y + face.getModY(), z + face.getModZ()));
                if (n != null) {
                    return n;
                }
                Block empty = mock(Block.class);
                when(empty.getType()).thenReturn(Material.AIR);
                when(empty.getBlockData()).thenReturn(mock(BlockData.class));
                return empty;
            });
        }

        private void put(int x, int y, int z, Block block) {
            blocks.put(key(x, y, z), block);
        }

        private static long key(int x, int y, int z) {
            return ((long) x & 0x3FFFFFF) << 38 | ((long) z & 0x3FFFFFF) << 12 | (y & 0xFFF);
        }
    }

    @Test
    void repairsALeftSinglePairIntoLeftRight() {
        // The #324 bug shape: breaking the left half first collapsed the
        // right half to single, so the restore left a (left, single) pair.
        FakeWorld w = new FakeWorld();
        w.chest(0, 64, 0, BlockFace.NORTH, Chest.Type.LEFT);
        w.chest(1, 64, 0, BlockFace.NORTH, Chest.Type.SINGLE);

        ChestRepair.repair(w.world, 0, 64, 0);

        // North-facing: the +X neighbour is the clockwise side, so (0) is the
        // left half and (1) the right - a coherent pair, fixed from either.
        assertThat(w.typeAt(0, 64, 0)).isEqualTo(Chest.Type.LEFT);
        assertThat(w.typeAt(1, 64, 0)).isEqualTo(Chest.Type.RIGHT);
    }

    @Test
    void repairingEitherHalfFixesBoth() {
        // Same pair, but repair is invoked on the half that is already 'left'
        // -> it must still correct the single partner (fix-both property that
        // makes the pass order-independent across chunks/batches).
        FakeWorld w = new FakeWorld();
        w.chest(0, 64, 0, BlockFace.NORTH, Chest.Type.SINGLE);
        w.chest(1, 64, 0, BlockFace.NORTH, Chest.Type.RIGHT);

        ChestRepair.repair(w.world, 1, 64, 0);

        assertThat(w.typeAt(0, 64, 0)).isEqualTo(Chest.Type.LEFT);
        assertThat(w.typeAt(1, 64, 0)).isEqualTo(Chest.Type.RIGHT);
    }

    @Test
    void loneChestBecomesSingle() {
        // A restored half whose partner is genuinely gone must not stay
        // 'left' claiming a neighbour that no longer exists.
        FakeWorld w = new FakeWorld();
        w.chest(0, 64, 0, BlockFace.NORTH, Chest.Type.LEFT);
        w.air(1, 64, 0);

        ChestRepair.repair(w.world, 0, 64, 0);

        assertThat(w.typeAt(0, 64, 0)).isEqualTo(Chest.Type.SINGLE);
    }

    @Test
    void differentFacingNeighbourIsNotAPartner() {
        FakeWorld w = new FakeWorld();
        w.chest(0, 64, 0, BlockFace.NORTH, Chest.Type.LEFT);
        w.chest(1, 64, 0, BlockFace.SOUTH, Chest.Type.SINGLE);

        ChestRepair.repair(w.world, 0, 64, 0);

        assertThat(w.typeAt(0, 64, 0)).isEqualTo(Chest.Type.SINGLE);
        assertThat(w.typeAt(1, 64, 0)).isEqualTo(Chest.Type.SINGLE);
    }

    @Test
    void trappedChestNeighbourIsNotAPartner() {
        // A chest and a trapped chest never merge, even adjacent + same facing.
        FakeWorld w = new FakeWorld();
        w.chest(0, 64, 0, BlockFace.NORTH, Chest.Type.LEFT);
        w.trappedChest(1, 64, 0, BlockFace.NORTH, Chest.Type.SINGLE);

        ChestRepair.repair(w.world, 0, 64, 0);

        assertThat(w.typeAt(0, 64, 0)).isEqualTo(Chest.Type.SINGLE);
    }

    @Test
    void westFacingPairConnectsAlongZ() {
        // Facing WEST, the clockwise neighbour is -Z (north). Proves the
        // rotation math is not hard-coded to the north/X case.
        FakeWorld w = new FakeWorld();
        w.chest(5, 70, 9, BlockFace.WEST, Chest.Type.SINGLE);
        w.chest(5, 70, 8, BlockFace.WEST, Chest.Type.SINGLE);

        ChestRepair.repair(w.world, 5, 70, 9);

        // (5,70,9) clockwise (for WEST) is north = -Z = (5,70,8): that side
        // is the left half.
        assertThat(w.typeAt(5, 70, 9)).isEqualTo(Chest.Type.LEFT);
        assertThat(w.typeAt(5, 70, 8)).isEqualTo(Chest.Type.RIGHT);
    }

    @Test
    void nonChestBlockIsIgnored() {
        FakeWorld w = new FakeWorld();
        w.air(0, 64, 0);
        // Must not throw when the restored cell is not a chest at all.
        ChestRepair.repair(w.world, 0, 64, 0);
    }
}
