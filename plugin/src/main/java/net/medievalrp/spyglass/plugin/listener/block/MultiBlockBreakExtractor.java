package net.medievalrp.spyglass.plugin.listener.block;

import java.time.Instant;
import java.util.stream.Stream;
import net.medievalrp.spyglass.api.event.BlockBreakRecord;
import net.medievalrp.spyglass.api.event.BlockSnapshot;
import net.medievalrp.spyglass.api.extension.EventExtractor;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.plugin.listener.ExtractorSupport;
import net.medievalrp.spyglass.plugin.util.BlockLocations;
import net.medievalrp.spyglass.plugin.util.BlockSnapshots;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Bed;
import org.bukkit.block.data.type.Door;
import org.bukkit.event.block.BlockBreakEvent;
import org.jetbrains.annotations.ApiStatus;

/**
 * Two-high and two-part blocks (beds, doors, tall flowers, tall grass, kelp)
 * only fire a single BlockBreakEvent; the other half is removed by a physics
 * update. This extractor emits a companion break record for that second half
 * so a rollback can restore the whole block.
 */
@ApiStatus.Internal
public final class MultiBlockBreakExtractor implements EventExtractor<BlockBreakEvent, BlockBreakRecord> {

    private final ExtractorSupport support;

    public MultiBlockBreakExtractor(ExtractorSupport support) {
        this.support = support;
    }

    @Override
    public Class<BlockBreakEvent> eventType() {
        return BlockBreakEvent.class;
    }

    @Override
    public Stream<BlockBreakRecord> extract(BlockBreakEvent event) {
        Block block = event.getBlock();
        Block partner = partnerOf(block);
        if (partner == null || partner.getType() == Material.AIR) {
            return Stream.empty();
        }
        Instant occurred = support.now();
        BlockSnapshot original = BlockSnapshots.capture(partner.getState());
        BlockSnapshot after = BlockSnapshots.air();
        BlockLocation location = BlockLocations.fromLocation(partner.getLocation());
        return Stream.of(new BlockBreakRecord(
                support.newId(),
                1,
                "break",
                occurred,
                support.expiresAt(occurred),
                support.playerOrigin(),
                support.playerSource(event.getPlayer()),
                location,
                original.material().name(),
                original,
                after));
    }

    private Block partnerOf(Block block) {
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

    private static boolean isTallPlant(Material material) {
        return switch (material) {
            case TALL_GRASS, LARGE_FERN, SUNFLOWER, PEONY, ROSE_BUSH, LILAC,
                 PITCHER_PLANT, TALL_SEAGRASS, SMALL_DRIPLEAF -> true;
            default -> false;
        };
    }
}
