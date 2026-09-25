package regression.probe;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/** Isolated fixture only: modifies the test player's inventory. */
public final class ToolRemovalProbe extends JavaPlugin {
    private static final NamespacedKey KEY = new NamespacedKey("spyglass", "wand");
    private static boolean tagged(ItemStack item) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(KEY, PersistentDataType.BYTE);
    }
    private static ItemStack wand(Material material, int amount) {
        ItemStack item = new ItemStack(material, amount);
        var meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(KEY, PersistentDataType.BYTE, (byte)1);
        item.setItemMeta(meta);
        return item;
    }
    private void result(String result) {
        try { java.nio.file.Files.writeString(java.nio.file.Path.of("tool-removal-result.txt"), result); }
        catch (java.io.IOException ex) { throw new RuntimeException(ex); }
    }
    private void step(Player player, boolean op, Runnable action) {
        getServer().getScheduler().runTaskLater(this, () -> {
            try { action.run(); }
            catch (Throwable failure) { result("FAIL " + failure); player.setOp(op); }
        }, 10L);
    }
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (sender instanceof Player || args.length != 1) return false;
        Player player = getServer().getPlayerExact(args[0]);
        if (player == null) { sender.sendMessage("FAIL player missing"); return true; }
        boolean op = player.isOp();
        result("RUNNING");
        player.setOp(true);
        var inv = player.getInventory();
        inv.clear(); inv.setHeldItemSlot(0); player.setItemOnCursor(null);
        player.performCommand("sg tool");
        step(player, op, () -> {
            if (!tagged(inv.getItemInMainHand())) throw new AssertionError("activation did not give tool");
            inv.setItem(8, wand(Material.REDSTONE_LAMP, 3));
            inv.setItemInOffHand(wand(Material.STONE, 2));
            inv.setHelmet(wand(Material.DIAMOND_HELMET, 1));
            player.setItemOnCursor(wand(Material.BARRIER, 1));
            inv.setItem(9, new ItemStack(Material.REDSTONE_LAMP, 7));
            player.performCommand("sg tool");
            step(player, op, () -> {
                for (ItemStack item : inv.getContents()) if (tagged(item)) throw new AssertionError("tagged item survived deactivation");
                if (tagged(player.getItemOnCursor())) throw new AssertionError("tagged cursor survived");
                if (inv.getItem(9) == null || inv.getItem(9).getAmount() != 7) throw new AssertionError("ordinary lamps lost");
                player.performCommand("sg tool");
                step(player, op, () -> {
                    int count = 0;
                    for (ItemStack item : inv.getContents()) if (tagged(item)) count += item.getAmount();
                    if (count != 1) throw new AssertionError("reactivation count: " + count);
                    player.setItemOnCursor(new ItemStack(Material.DIAMOND, 4));
                    player.performCommand("sg tool");
                    step(player, op, () -> {
                        if (tagged(inv.getItemInMainHand()) || player.getItemOnCursor().getAmount() != 4)
                            throw new AssertionError("repeat deactivation or ordinary cursor preservation failed");
                        result("PASS tool deactivation removes all PDC tools, preserves ordinary items, and reactivates once");
                        player.setOp(op);
                    });
                });
            });
        });
        return true;
    }
}
