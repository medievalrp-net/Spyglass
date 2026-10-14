package regression.probe;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.command.*;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;

public final class WandProbe extends JavaPlugin {
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (sender instanceof Player || args.length != 1) return false;
        Player player = getServer().getPlayerExact(args[0]);
        if (player == null) { sender.sendMessage("FAIL missing player"); return true; }
        boolean op = player.isOp();
        try {
            player.setOp(false);
            ItemStack wand = new ItemStack(Material.REDSTONE_LAMP);
            var meta = wand.getItemMeta();
            meta.getPersistentDataContainer().set(new NamespacedKey("spyglass", "wand"), PersistentDataType.BYTE, (byte)1);
            wand.setItemMeta(meta);
            Block block = player.getWorld().getBlockAt(65,80,66);
            for (EquipmentSlot hand : new EquipmentSlot[] {EquipmentSlot.HAND, EquipmentSlot.OFF_HAND}) {
                var place = new BlockPlaceEvent(block, block.getState(), block.getRelative(BlockFace.DOWN), wand, player, true, hand);
                getServer().getPluginManager().callEvent(place);
                if (!place.isCancelled()) throw new AssertionError("tagged wand placed with " + hand);
                var interact = new PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK, wand, block.getRelative(BlockFace.DOWN), BlockFace.UP, hand);
                getServer().getPluginManager().callEvent(interact);
                if (interact.useItemInHand() != org.bukkit.event.Event.Result.DENY) throw new AssertionError("tagged wand used with " + hand);
            }
            var ordinary = new BlockPlaceEvent(block, block.getState(), block.getRelative(BlockFace.DOWN), new ItemStack(Material.REDSTONE_LAMP), player, true, EquipmentSlot.HAND);
            getServer().getPluginManager().callEvent(ordinary);
            if (ordinary.isCancelled()) throw new AssertionError("ordinary lamp cancelled");
            sender.sendMessage("PASS live Paper wand guards: both hands, inactive non-op, old material; ordinary lamp allowed");
        } catch (Throwable failure) { sender.sendMessage("FAIL " + failure); }
        finally { player.setOp(op); }
        return true;
    }
}
