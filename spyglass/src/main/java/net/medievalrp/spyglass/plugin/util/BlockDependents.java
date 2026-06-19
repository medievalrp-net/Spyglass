package net.medievalrp.spyglass.plugin.util;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.medievalrp.spyglass.api.util.BlockLocation;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.type.Switch;
import org.jetbrains.annotations.ApiStatus;

/**
 * Classifies a block by how it attaches to its neighbors, so a listener
 * can compute "which of my neighbors would fall if I broke this?" at
 * event-fire time and emit companion break records. v1 had a similar
 * {@code DependantStyle} taxonomy in its {@code EventBreakListener};
 * this is a fresh rewrite using Bukkit's {@link Tag} constants where
 * available so 1.21+ blocks are covered without hand-enumerating new
 * flowers every release.
 *
 * <p>Two-block-tall blocks (doors, beds, tall flowers) are intentionally
 * NOT in this taxonomy — they are symmetric pairs handled separately
 * by {@link net.medievalrp.spyglass.plugin.listener.block.MultiBlockBreakListener}.
 */
@ApiStatus.Internal
public final class BlockDependents {

    public enum Style {
        /**
         * Attaches to the side of a supporting block; the supporter is
         * the block at {@code neighbor.getFacing()}. Wall torches, wall
         * signs, ladders, wall banners, tripwire hooks, wall coral fans.
         */
        WALL,

        /**
         * Rests on top of its supporter. Torches (standing), flowers,
         * saplings, grass, crops, carpets, rails, pressure plates,
         * redstone dust, repeaters, comparators, snow layers, standing
         * signs.
         */
        BOTTOM,

        /**
         * Attaches to any of 6 faces. Levers and buttons — Bukkit
         * exposes the attached face via {@link Switch#getAttachedFace()}.
         */
        ALL,

        NONE
    }

    /**
     * The six axial faces, cached so the hot break path never clones
     * {@link BlockFace#values()} (which copies all ~80 enum constants) and
     * re-filters it on every block break. Order matches the previous
     * {@code values()}-then-{@code isAxial} scan so dependent ordering is
     * unchanged.
     */
    private static final BlockFace[] AXIAL_FACES = {
            BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH,
            BlockFace.WEST, BlockFace.UP, BlockFace.DOWN,
    };

    private BlockDependents() {
    }

    /** Walk each face of {@code broken} and collect neighbors that are attached to it. */
    public static List<Block> collectDependents(Block broken) {
        // The common break (stone, dirt, ore) has no attached neighbors, so
        // keep that path allocation-free: cached AXIAL_FACES (no values()
        // clone), no ArrayList until the first dependent, and no Block
        // wrapper for NONE-style neighbors. A Material probe via
        // World.getType(x,y,z) returns an enum constant (no CraftBlock),
        // so getRelative() — which does allocate a Block — is paid only for
        // a neighbor that could actually attach.
        World world = broken.getWorld();
        int x = broken.getX();
        int y = broken.getY();
        int z = broken.getZ();
        List<Block> out = null;
        for (BlockFace face : AXIAL_FACES) {
            Material neighborType = world.getType(
                    x + face.getModX(), y + face.getModY(), z + face.getModZ());
            if (styleOf(neighborType) == Style.NONE) {
                continue;
            }
            Block neighbor = broken.getRelative(face);
            if (isAttachedTo(neighbor, face)) {
                if (out == null) {
                    out = new ArrayList<>(4);
                }
                out.add(neighbor);
            }
        }
        return out == null ? List.of() : out;
    }

    /**
     * Dependents of every host in {@code hosts} that aren't themselves in
     * {@code hosts} — i.e. attached blocks the explosion / burn won't have
     * already destroyed directly. The first occurrence of each location is
     * kept; later duplicates from sibling hosts are dropped so a torch
     * attached to two destroyed walls only emits one record.
     */
    public static List<Block> collectDependentsBeyond(List<Block> hosts) {
        Set<BlockLocation> seen = new HashSet<>(hosts.size() * 2);
        for (Block host : hosts) {
            seen.add(BlockLocations.fromLocation(host.getLocation()));
        }
        List<Block> out = new ArrayList<>();
        for (Block host : hosts) {
            for (Block dep : collectDependents(host)) {
                BlockLocation depLoc = BlockLocations.fromLocation(dep.getLocation());
                if (seen.add(depLoc)) {
                    out.add(dep);
                }
            }
        }
        return out;
    }

    /**
     * Is {@code candidate} attached to the block that sits in the
     * direction {@code face.getOppositeFace()} from it? i.e. when the
     * neighbor-of-broken was reached by walking {@code face} from
     * {@code broken}, does it consider {@code broken} its supporter?
     */
    public static boolean isAttachedTo(Block candidate, BlockFace directionFromHost) {
        Style style = styleOf(candidate.getType());
        return switch (style) {
            case BOTTOM -> directionFromHost == BlockFace.UP;
            case WALL -> directionFromHost != BlockFace.UP
                    && directionFromHost != BlockFace.DOWN
                    && wallFacesHost(candidate, directionFromHost);
            case ALL -> switchAttachesTo(candidate, directionFromHost.getOppositeFace());
            case NONE -> false;
        };
    }

    /**
     * For WALL-style blocks with a {@link Directional} blockdata,
     * {@code getFacing()} points AWAY from the supporting wall. So if
     * we reached this block by walking {@code face} from the host, the
     * block's facing should equal {@code face} — it's pointing in the
     * same direction we walked (away from the host).
     */
    private static boolean wallFacesHost(Block block, BlockFace walkedFace) {
        BlockData data = block.getBlockData();
        if (!(data instanceof Directional directional)) {
            // Ladders, wall signs, etc. without a Directional blockdata:
            // trust the WALL classification and accept any horizontal
            // attachment. More permissive than v1 but safer for rollback.
            return true;
        }
        return directional.getFacing() == walkedFace;
    }

    /**
     * For ALL-style blocks (levers, buttons), Bukkit's {@link Switch}
     * exposes the actual attachment face via {@link Switch#getAttachedFace()}.
     * v1 used the generic {@code Directional.getFacing()} which returns
     * the horizontal rotation instead of the attachment, missing floor
     * and ceiling mounts — fixed here.
     */
    private static boolean switchAttachesTo(Block block, BlockFace expectedHostFace) {
        BlockData data = block.getBlockData();
        if (data instanceof Switch sw) {
            // FaceAttachable.AttachedFace says ceiling/floor/wall;
            // getFacing() narrows the WALL case to a specific BlockFace.
            return switch (sw.getAttachedFace()) {
                case CEILING -> expectedHostFace == BlockFace.UP;
                case FLOOR -> expectedHostFace == BlockFace.DOWN;
                case WALL -> sw.getFacing().getOppositeFace() == expectedHostFace;
            };
        }
        if (data instanceof Directional directional) {
            return directional.getFacing().getOppositeFace() == expectedHostFace;
        }
        return false;
    }

    public static Style styleOf(Material material) {
        // ALL — attaches to any face
        if (Tag.BUTTONS.isTagged(material) || material == Material.LEVER) {
            return Style.ALL;
        }
        // WALL — horizontal attachment to a neighboring wall
        if (Tag.WALL_SIGNS.isTagged(material)
                || Tag.WALL_CORALS.isTagged(material)
                || material.name().endsWith("_WALL_BANNER")
                || material.name().endsWith("_WALL_FAN")
                || material.name().endsWith("_WALL_TORCH")
                || material.name().endsWith("_WALL_HEAD")
                || material.name().endsWith("_WALL_SKULL")) {
            return Style.WALL;
        }
        switch (material) {
            case WALL_TORCH:
            case REDSTONE_WALL_TORCH:
            case SOUL_WALL_TORCH:
            case LADDER:
            case TRIPWIRE_HOOK:
            case VINE:
            case GLOW_LICHEN:
            case SCULK_VEIN:
                return Style.WALL;
            default:
                break;
        }
        // BOTTOM — rests on the block below
        if (Tag.SAPLINGS.isTagged(material)
                || Tag.SMALL_FLOWERS.isTagged(material)
                || Tag.STANDING_SIGNS.isTagged(material)
                || Tag.WOOL_CARPETS.isTagged(material)
                || Tag.RAILS.isTagged(material)
                || Tag.CROPS.isTagged(material)
                || Tag.FLOWER_POTS.isTagged(material)
                || Tag.PRESSURE_PLATES.isTagged(material)
                || Tag.CANDLES.isTagged(material)) {
            return Style.BOTTOM;
        }
        switch (material) {
            case TORCH:
            case REDSTONE_TORCH:
            case SOUL_TORCH:
            case REDSTONE_WIRE:
            case REPEATER:
            case COMPARATOR:
            case SNOW:
            case DEAD_BUSH:
            case SHORT_GRASS:
            case FERN:
            case LARGE_FERN:
            case SEAGRASS:
            case TALL_SEAGRASS:
            case SEA_PICKLE:
            case COCOA:
            case CHORUS_FLOWER:
            case COBWEB:
            case BROWN_MUSHROOM:
            case RED_MUSHROOM:
            case LILY_PAD:
            case KELP:
            case KELP_PLANT:
            case HANGING_ROOTS:
            case POINTED_DRIPSTONE:
            case SMALL_DRIPLEAF:
            case BIG_DRIPLEAF:
            case BIG_DRIPLEAF_STEM:
            case AMETHYST_CLUSTER:
            case SMALL_AMETHYST_BUD:
            case MEDIUM_AMETHYST_BUD:
            case LARGE_AMETHYST_BUD:
            case PINK_PETALS:
            case SUGAR_CANE:
            case BAMBOO:
            case CACTUS:
                return Style.BOTTOM;
            default:
                return Style.NONE;
        }
    }
}
