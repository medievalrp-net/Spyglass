package net.medievalrp.spyglass.plugin.util;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.medievalrp.spyglass.api.util.BlockLocation;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Bed;
import org.bukkit.block.data.type.Door;
import org.jetbrains.annotations.ApiStatus;

/**
 * Symmetric / stacked block partners that vanilla physics removes
 * silently when their host is destroyed. v1 walked these inline inside
 * {@code saveMultiBreak} from its explosion handler; v2 originally only
 * handled them in {@link
 * net.medievalrp.spyglass.plugin.listener.block.MultiBlockBreakListener}
 * — which listens to {@code BlockBreakEvent} only — so blowing up a
 * bed foot or a sugar-cane stack lost everything except the directly-
 * exploded block. This util pulls the shared partner-detection out so
 * both player-break and explosion paths can share it.
 *
 * <p>Two flavors:
 * <ul>
 *   <li><b>Pair partner</b> (bed head/foot, door top/bottom, tall flower
 *       top/bottom): exactly one related block, which lives on a fixed
 *       face determined by the BlockData.</li>
 *   <li><b>Stacked partner</b> (cactus, sugar cane, kelp, bamboo): zero
 *       or more blocks above, all the same material, walking up until
 *       the chain ends.</li>
 * </ul>
 */
@ApiStatus.Internal
public final class MultiBlockPartners {

    private MultiBlockPartners() {
    }

    /**
     * Pair partner of {@code block} (bed/door/tall flower) or {@code null}
     * if the block isn't part of such a pair. The partner block isn't
     * checked for AIR — the caller decides whether to record it based on
     * its current material.
     */
    public static Block partnerOf(Block block) {
        BlockData data = block.getBlockData();
        if (data instanceof Bed bed) {
            return bed.getPart() == Bed.Part.HEAD
                    ? block.getRelative(bed.getFacing().getOppositeFace())
                    : block.getRelative(bed.getFacing());
        }
        if (data instanceof Door door) {
            return door.getHalf() == Bisected.Half.TOP
                    ? block.getRelative(BlockFace.DOWN)
                    : block.getRelative(BlockFace.UP);
        }
        if (isTallPlant(block.getType()) && data instanceof Bisected bisected) {
            return bisected.getHalf() == Bisected.Half.TOP
                    ? block.getRelative(BlockFace.DOWN)
                    : block.getRelative(BlockFace.UP);
        }
        return null;
    }

    /**
     * Walk upward collecting same-material stacked blocks (cactus, sugar
     * cane, kelp, bamboo). When the bottom of one of these is destroyed,
     * the rest fall via vanilla physics without ever firing a break event;
     * v1 walked this chain from {@code saveMultiBreak} so rollbacks could
     * restore the whole stack. Returns the upper segments only — the
     * caller already has {@code start}.
     */
    public static List<Block> stackAbove(Block start) {
        Material type = start.getType();
        if (!isStackedPlant(type)) {
            return List.of();
        }
        List<Block> out = new ArrayList<>();
        Block step = start.getRelative(BlockFace.UP);
        while (step.getType() == type) {
            out.add(step);
            step = step.getRelative(BlockFace.UP);
        }
        return out;
    }

    /**
     * Convenience for explosion handlers: collect every multi-block
     * partner (pair partners + stack-aboves) for the supplied host list,
     * deduped against the hosts themselves. The host {@code blockList}
     * is what the explosion is already going to record; we want the
     * extras vanilla physics will silently remove.
     */
    public static List<Block> partnersBeyond(List<Block> hosts) {
        Set<BlockLocation> seen = new HashSet<>(hosts.size() * 2);
        for (Block host : hosts) {
            seen.add(BlockLocations.fromLocation(host.getLocation()));
        }
        List<Block> out = new ArrayList<>();
        for (Block host : hosts) {
            Block pair = partnerOf(host);
            if (pair != null && pair.getType() != Material.AIR
                    && seen.add(BlockLocations.fromLocation(pair.getLocation()))) {
                out.add(pair);
            }
            for (Block stacked : stackAbove(host)) {
                if (seen.add(BlockLocations.fromLocation(stacked.getLocation()))) {
                    out.add(stacked);
                }
            }
        }
        return out;
    }

    public static boolean isTallPlant(Material material) {
        return switch (material) {
            case TALL_GRASS, LARGE_FERN, SUNFLOWER, PEONY, ROSE_BUSH, LILAC,
                 PITCHER_PLANT, TALL_SEAGRASS, SMALL_DRIPLEAF -> true;
            default -> false;
        };
    }

    public static boolean isStackedPlant(Material material) {
        return material == Material.CACTUS
                || material == Material.SUGAR_CANE
                || material == Material.KELP_PLANT
                || material == Material.KELP
                || material == Material.BAMBOO
                || material == Material.BAMBOO_SAPLING;
    }
}
