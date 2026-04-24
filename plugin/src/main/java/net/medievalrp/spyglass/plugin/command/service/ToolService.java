package net.medievalrp.spyglass.plugin.command.service;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.medievalrp.spyglass.plugin.tool.ToolStateStore;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class ToolService {

    private final ToolStateStore store;
    private final Material wandMaterial;
    private final WandHandout handout;
    private final Set<UUID> active = ConcurrentHashMap.newKeySet();

    public ToolService(ToolStateStore store, Material wandMaterial) {
        this(store, wandMaterial, WandHandout.bukkit());
    }

    public ToolService(ToolStateStore store, Material wandMaterial, WandHandout handout) {
        this.store = store;
        this.wandMaterial = wandMaterial;
        this.handout = handout;
        active.addAll(store.loadActive());
    }

    public void toggle(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ServiceSupport.errorMessage("The inspection wand is player-only."));
            return;
        }
        UUID id = player.getUniqueId();
        boolean wasActive = active.remove(id);
        if (wasActive) {
            store.disable(id);
            handout.take(player, wandMaterial);
            sender.sendMessage(ServiceSupport.infoMessage("Inspection wand disabled."));
            return;
        }
        active.add(id);
        store.enable(id);
        handout.give(player, wandMaterial);
        sender.sendMessage(ServiceSupport.infoMessage(
                "Inspection wand active \u2014 right-click a block to inspect; wand-held break/place triggers a lookup."));
    }

    public boolean isActive(UUID playerId) {
        return active.contains(playerId);
    }

    public Material wandMaterial() {
        return wandMaterial;
    }

    public interface WandHandout {
        void give(Player player, Material material);

        void take(Player player, Material material);

        static WandHandout bukkit() {
            return new WandHandout() {
                @Override
                public void give(Player player, Material material) {
                    ItemStack stack = new ItemStack(material, 1);
                    if (player.getInventory().firstEmpty() >= 0) {
                        player.getInventory().addItem(stack);
                        return;
                    }
                    player.getWorld().dropItemNaturally(player.getLocation(), stack);
                }

                @Override
                public void take(Player player, Material material) {
                    player.getInventory().remove(material);
                }
            };
        }
    }
}
