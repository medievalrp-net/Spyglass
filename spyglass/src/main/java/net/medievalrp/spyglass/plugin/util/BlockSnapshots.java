package net.medievalrp.spyglass.plugin.util;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.medievalrp.spyglass.api.event.BlockSnapshot;
import net.medievalrp.spyglass.api.event.StoredItem;
import org.bukkit.Material;
import org.bukkit.block.Banner;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.Jukebox;
import org.bukkit.block.Sign;
import org.bukkit.block.banner.Pattern;
import org.bukkit.block.sign.Side;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

public final class BlockSnapshots {

    private BlockSnapshots() {
    }

    public static BlockSnapshot capture(BlockState state) {
        List<StoredItem> items = List.of();
        if (state instanceof Container container) {
            items = captureInventory(container.getSnapshotInventory());
        }

        List<String> signFront = List.of();
        List<String> signBack = List.of();
        if (state instanceof Sign sign) {
            signFront = lines(sign, Side.FRONT);
            signBack = lines(sign, Side.BACK);
        }

        List<String> bannerPatterns = List.of();
        if (state instanceof Banner banner) {
            bannerPatterns = banner.getPatterns().stream()
                    .map(pattern -> pattern.getColor().name() + ":" + pattern.getPattern().getIdentifier())
                    .toList();
        }

        String jukeboxRecord = null;
        if (state instanceof Jukebox jukebox) {
            ItemStack record = jukebox.getSnapshotInventory().getItem(0);
            jukeboxRecord = ItemSerialization.encode(record);
        }

        return new BlockSnapshot(
                state.getType(),
                state.getBlockData().getAsString(),
                items,
                signFront,
                signBack,
                bannerPatterns,
                jukeboxRecord);
    }

    public static BlockSnapshot air() {
        return new BlockSnapshot(Material.AIR, "minecraft:air",
                List.of(), List.of(), List.of(), List.of(), null);
    }

    /**
     * Plain material + blockData snapshot for callers that don't have a
     * {@link BlockState} handy (FAWE chunk diff, brush/vault delayed checks).
     * Inventory / sign / banner / jukebox lists are empty — callers pass a
     * {@link BlockState} to {@link #capture} when they want that data.
     */
    public static BlockSnapshot of(Material material, String blockData) {
        return new BlockSnapshot(material, blockData,
                List.of(), List.of(), List.of(), List.of(), null);
    }

    /**
     * Resolves a stored material string (e.g. from a v1 MaterialType field or
     * a FAWE blockData string) to a Bukkit {@link Material}. Falls back to
     * {@link Material#AIR} on null/blank/unknown input.
     */
    public static Material matchMaterial(String name) {
        if (name == null || name.isBlank()) {
            return Material.AIR;
        }
        Material direct = Material.matchMaterial(name, false);
        if (direct != null) {
            return direct;
        }
        Material legacy = Material.matchMaterial(name, true);
        return legacy != null ? legacy : Material.AIR;
    }

    private static List<StoredItem> captureInventory(Inventory inventory) {
        List<StoredItem> items = new ArrayList<>();
        ItemStack[] contents = inventory.getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            StoredItem item = ItemSerialization.storedItem(slot, contents[slot]);
            if (item != null) {
                items.add(item);
            }
        }
        return List.copyOf(items);
    }

    private static List<String> lines(Sign sign, Side side) {
        return sign.getSide(side).lines().stream()
                .map(PlainTextComponentSerializer.plainText()::serialize)
                .toList();
    }
}
