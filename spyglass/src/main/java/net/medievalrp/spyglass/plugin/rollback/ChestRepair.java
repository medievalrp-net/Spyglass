package net.medievalrp.spyglass.plugin.rollback;

import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Chest;
import org.jetbrains.annotations.ApiStatus;

/**
 * Re-pairs restored chest halves so a rolled-back double chest comes back
 * coherent (#324).
 *
 * <p>Breaking the first half of a double chest makes vanilla collapse the
 * surviving half from {@code type=right} to {@code type=single} before that
 * half's own break is recorded. Each half is therefore restored to a state
 * captured at a different instant - {@code left} for the first, {@code single}
 * for the second - and the direct-write apply path preserves those recorded
 * states exactly, so the pair comes back mismatched (a {@code left} next to a
 * {@code single}) and no longer forms one 54-slot inventory.
 *
 * <p>After the engine writes a chest cell, {@link #repair} re-derives its
 * connectivity from its neighbours the way placement does and, when it finds a
 * matching partner, fixes <em>both</em> halves. Fixing both is what makes the
 * result independent of which half was written or repaired first, so a pair
 * split across chunk or batch boundaries still converges: whichever half is
 * processed once both are in the world repairs the pair.
 *
 * <p>Main thread only (Bukkit block access). Cheap: only ever called for the
 * chest cells an op actually wrote, which a grief rollback has few of.
 */
@ApiStatus.Internal
final class ChestRepair {

    private ChestRepair() {
    }

    static void repair(World world, int x, int y, int z) {
        Block block = world.getBlockAt(x, y, z);
        if (!(block.getBlockData() instanceof Chest chest)) {
            return;
        }
        BlockFace facing = chest.getFacing();
        // Vanilla ChestBlock.getConnectedDirection: a LEFT chest's partner
        // sits at facing.clockwise, a RIGHT chest's at facing.counter-
        // clockwise. Invert that to derive our type from whichever
        // neighbour is a matching chest.
        Block clockwise = block.getRelative(clockwise(facing));
        Block counter = block.getRelative(counterClockwise(facing));
        if (isPartner(block, clockwise, facing)) {
            setTypes(block, Chest.Type.LEFT, clockwise, Chest.Type.RIGHT);
        } else if (isPartner(block, counter, facing)) {
            setTypes(block, Chest.Type.RIGHT, counter, Chest.Type.LEFT);
        } else if (chest.getType() != Chest.Type.SINGLE) {
            chest.setType(Chest.Type.SINGLE);
            block.setBlockData(chest, false);
        }
    }

    // A partner is an identical-material chest (a chest never pairs with a
    // trapped chest) sharing this chest's facing.
    private static boolean isPartner(Block self, Block neighbour, BlockFace facing) {
        return self.getType() == neighbour.getType()
                && neighbour.getBlockData() instanceof Chest other
                && other.getFacing() == facing;
    }

    private static void setTypes(Block a, Chest.Type typeA, Block b, Chest.Type typeB) {
        applyType(a, typeA);
        applyType(b, typeB);
    }

    private static void applyType(Block block, Chest.Type type) {
        BlockData data = block.getBlockData();
        if (data instanceof Chest chest && chest.getType() != type) {
            chest.setType(type);
            block.setBlockData(chest, false);
        }
    }

    // Horizontal clockwise / counter-clockwise viewed from above, matching
    // net.minecraft.core.Direction. Non-horizontal facings never occur on a
    // chest, so they map to themselves and yield no partner.
    private static BlockFace clockwise(BlockFace facing) {
        return switch (facing) {
            case NORTH -> BlockFace.EAST;
            case EAST -> BlockFace.SOUTH;
            case SOUTH -> BlockFace.WEST;
            case WEST -> BlockFace.NORTH;
            default -> facing;
        };
    }

    private static BlockFace counterClockwise(BlockFace facing) {
        return switch (facing) {
            case NORTH -> BlockFace.WEST;
            case WEST -> BlockFace.SOUTH;
            case SOUTH -> BlockFace.EAST;
            case EAST -> BlockFace.NORTH;
            default -> facing;
        };
    }
}
