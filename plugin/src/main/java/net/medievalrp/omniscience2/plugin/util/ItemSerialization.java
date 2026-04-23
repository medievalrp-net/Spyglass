package net.medievalrp.omniscience2.plugin.util;

import java.util.Base64;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

public final class ItemSerialization {

    private ItemSerialization() {
    }

    public static String encode(ItemStack itemStack) {
        if (itemStack == null) {
            return null;
        }
        return Base64.getEncoder().encodeToString(itemStack.serializeAsBytes());
    }

    public static ItemStack decode(String data) {
        if (data == null || data.isBlank()) {
            return null;
        }
        return ItemStack.deserializeBytes(Base64.getDecoder().decode(data));
    }

    public static net.medievalrp.omniscience2.api.event.StoredItem storedItem(int slot, ItemStack itemStack) {
        if (itemStack == null || itemStack.getType() == Material.AIR) {
            return null;
        }
        return new net.medievalrp.omniscience2.api.event.StoredItem(slot, itemStack.getType().name(), encode(itemStack));
    }
}
