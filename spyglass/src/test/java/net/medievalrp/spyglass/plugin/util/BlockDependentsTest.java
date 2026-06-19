package net.medievalrp.spyglass.plugin.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import net.medievalrp.spyglass.plugin.util.BlockDependents;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Directional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/**
 * Allocation contract for {@link BlockDependents#collectDependents} (#104).
 *
 * <p>The block-break path runs this on every break, so the no-dependent
 * common case (mining stone) must allocate nothing: the shared empty list,
 * no per-call {@code BlockFace[]} clone, and no {@code Block} wrapper for
 * NONE-style neighbors (decided by a cheap {@code World.getType} probe).
 * The dependent case must return the same blocks in the same order as
 * before.
 *
 * <p>{@code styleOf()} touches Bukkit {@link Tag} constants, which are
 * initialized from the server registry on first access and would NPE in a
 * plain JVM. We force that one-time init inside a {@code mockStatic} scope
 * with {@link Bukkit#getTag} stubbed to an empty tag; afterwards the
 * {@code Tag.*} fields are set for the JVM and the tests need no further
 * Bukkit stubbing. The switch-based styles used here (WALL_TORCH, LADDER,
 * TORCH) don't depend on tag membership, so an empty tag is enough.
 */
class BlockDependentsTest {

    @BeforeAll
    static void initBukkitTags() {
        // A plain Tag impl (not a Mockito mock): instantiating an interface
        // implementation does NOT initialize the Tag interface, so this
        // doesn't prematurely run Tag's server-backed field initializers
        // outside the stubbed scope below.
        Tag<Material> emptyTag = new Tag<>() {
            @Override
            public boolean isTagged(Material item) {
                return false;
            }

            @Override
            public java.util.Set<Material> getValues() {
                return java.util.Set.of();
            }

            @Override
            public org.bukkit.NamespacedKey getKey() {
                return org.bukkit.NamespacedKey.minecraft("spyglass_test_empty");
            }
        };
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getTag(anyString(), any(), any())).thenAnswer(inv -> emptyTag);
            // First touch of a Tag.* field runs the interface initializer,
            // which calls the (now stubbed) Bukkit.getTag for every tag.
            BlockDependents.styleOf(Material.STONE);
        }
    }

    @Test
    void noDependentsReturnsSharedEmptyListWithoutMaterializingBlocks() {
        World world = mock(World.class);
        when(world.getType(anyInt(), anyInt(), anyInt())).thenReturn(Material.STONE);
        Block broken = brokenAt(world, 0, 64, 0);

        List<Block> result = BlockDependents.collectDependents(broken);

        assertThat(result).isEmpty();
        assertThat(result)
                .as("empty result must be the shared List.of() singleton — no ArrayList allocated")
                .isSameAs(List.of());
        // NONE neighbors are decided by the getType probe; no Block wrapper.
        verify(broken, never()).getRelative(any(BlockFace.class));
    }

    @Test
    void collectsWallTorchDependentAndSkipsBlockWrapperForPlainNeighbors() {
        World world = mock(World.class);
        when(world.getType(anyInt(), anyInt(), anyInt())).thenReturn(Material.STONE);
        when(world.getType(1, 64, 0)).thenReturn(Material.WALL_TORCH); // EAST neighbor
        Block broken = brokenAt(world, 0, 64, 0);

        Block torch = mock(Block.class);
        when(torch.getType()).thenReturn(Material.WALL_TORCH);
        Directional facing = mock(Directional.class);
        when(facing.getFacing()).thenReturn(BlockFace.EAST); // points away from host
        when(torch.getBlockData()).thenReturn(facing);
        when(broken.getRelative(BlockFace.EAST)).thenReturn(torch);

        List<Block> result = BlockDependents.collectDependents(broken);

        assertThat(result).containsExactly(torch);
        // Only the one attachable neighbor is materialized; the other five
        // NONE-style faces never allocate a Block (#104 AC3).
        verify(broken).getRelative(BlockFace.EAST);
        verify(broken, never()).getRelative(BlockFace.UP);
        verify(broken, never()).getRelative(BlockFace.DOWN);
        verify(broken, never()).getRelative(BlockFace.NORTH);
        verify(broken, never()).getRelative(BlockFace.SOUTH);
        verify(broken, never()).getRelative(BlockFace.WEST);
    }

    @Test
    void preservesFaceOrderAcrossMultipleDependents() {
        World world = mock(World.class);
        when(world.getType(anyInt(), anyInt(), anyInt())).thenReturn(Material.STONE);
        when(world.getType(0, 64, -1)).thenReturn(Material.LADDER); // NORTH neighbor
        when(world.getType(0, 65, 0)).thenReturn(Material.TORCH);   // UP neighbor
        Block broken = brokenAt(world, 0, 64, 0);

        Block ladder = mock(Block.class);
        when(ladder.getType()).thenReturn(Material.LADDER);
        Directional ladderData = mock(Directional.class);
        when(ladderData.getFacing()).thenReturn(BlockFace.NORTH);
        when(ladder.getBlockData()).thenReturn(ladderData);
        when(broken.getRelative(BlockFace.NORTH)).thenReturn(ladder);

        Block torch = mock(Block.class); // standing torch rests on the host below
        when(torch.getType()).thenReturn(Material.TORCH);
        when(broken.getRelative(BlockFace.UP)).thenReturn(torch);

        List<Block> result = BlockDependents.collectDependents(broken);

        // AXIAL_FACES order is NORTH, EAST, SOUTH, WEST, UP, DOWN, so NORTH
        // precedes UP no matter the stubbing order — same order as the old
        // values()-then-isAxial scan.
        assertThat(result).containsExactly(ladder, torch);
    }

    private static Block brokenAt(World world, int x, int y, int z) {
        Block broken = mock(Block.class);
        when(broken.getWorld()).thenReturn(world);
        when(broken.getX()).thenReturn(x);
        when(broken.getY()).thenReturn(y);
        when(broken.getZ()).thenReturn(z);
        return broken;
    }
}
