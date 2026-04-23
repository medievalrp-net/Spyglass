package net.medievalrp.omniscience2.plugin.util;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.medievalrp.omniscience2.api.event.BlockSnapshot;
import net.medievalrp.omniscience2.api.event.StoredItem;
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
        return new BlockSnapshot(Material.AIR, Material.AIR.createBlockData().getAsString(), List.of(), List.of(), List.of(), List.of(), null);
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
