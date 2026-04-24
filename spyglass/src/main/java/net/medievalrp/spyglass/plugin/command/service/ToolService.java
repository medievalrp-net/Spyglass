package net.medievalrp.spyglass.plugin.command.service;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.medievalrp.spyglass.plugin.command.render.Feedback;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.medievalrp.spyglass.plugin.command.service.tool.ToolStateStore;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.ApiStatus;

/**
 * Wand toggle service. Mirrors v1's three-state cycle so repeated
 * {@code /sg tool} calls follow the same UX muscle memory:
 * <ol>
 * <li>Inactive → activate + give/move the wand, say "Activated".</li>
 * <li>Active but wand missing from inventory → add it, say "Added".</li>
 * <li>Active with wand in inventory but not in main hand → swap to hand,
 * say "Moved".</li>
 * <li>Active with wand already in main hand → deactivate, say "Deactivated".</li>
 * </ol>
 *
 * <p>Unlike v1, wand identity is checked via a PersistentDataContainer marker
 * (see {@link #WAND_KEY}) so a regular glowstone block sitting in the player's
 * inventory never trips the wand listener.
 */
@ApiStatus.Internal
public final class ToolService {

    public static final NamespacedKey WAND_KEY = new NamespacedKey("spyglass", "wand");

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
            sender.sendMessage(Feedback.error("This command cannot be run by non-players"));
            return;
        }
        UUID id = player.getUniqueId();
        PlayerInventory inv = player.getInventory();
        int slot = firstWandSlot(inv);
        boolean inHand = isWandInHand(player);

        if (active.contains(id)) {
            if (slot == -1) {
                handout.give(player, wandMaterial);
                player.sendMessage(Feedback.toolOk("Added the v1 data tool to your inventory."));
                return;
            }
            if (!inHand) {
                swapToMainHand(inv, slot);
                player.sendMessage(Feedback.toolOk("Moved the data tool to your hand."));
                return;
            }
            active.remove(id);
            store.disable(id);
            player.sendMessage(Feedback.toolOk("Deactivated the v1 Data Tool"));
            return;
        }

        // Inactive → activate. If the wand isn't in inventory, just add it;
        // if it's present but not in hand, swap it in silently. Either way,
        // the chat message mirrors v1 exactly.
        active.add(id);
        store.enable(id);
        if (slot == -1) {
            handout.give(player, wandMaterial);
            player.sendMessage(Feedback.toolOk("Added the v1 data tool to your inventory."));
            return;
        }
        if (!inHand) {
            swapToMainHand(inv, slot);
        }
        player.sendMessage(Component.text()
                .append(Component.text("Activated the v1 Data Tool ", NamedTextColor.GREEN))
                .append(Component.text("(" + wandMaterial.name() + ")", NamedTextColor.GRAY))
                .build());
    }

    public boolean isActive(UUID playerId) {
        return active.contains(playerId);
    }

    public Material wandMaterial() {
        return wandMaterial;
    }

    private int firstWandSlot(PlayerInventory inv) {
        ItemStack[] contents = inv.getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack stack = contents[i];
            if (stack != null && stack.getType() == wandMaterial && WandHandout.isWandItem(stack)) {
                return i;
            }
        }
        return -1;
    }

    private boolean isWandInHand(Player player) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        return hand != null && hand.getType() == wandMaterial && WandHandout.isWandItem(hand);
    }

    private static void swapToMainHand(PlayerInventory inv, int slot) {
        ItemStack currentHand = inv.getItemInMainHand();
        ItemStack wand = inv.getItem(slot);
        inv.setItemInMainHand(wand);
        inv.setItem(slot, currentHand == null || currentHand.getType() == Material.AIR ? null : currentHand);
    }

    public interface WandHandout {
        void give(Player player, Material material);

        void take(Player player, Material material);

        static WandHandout bukkit() {
            return new WandHandout() {
                @Override
                public void give(Player player, Material material) {
                    ItemStack stack = new ItemStack(material, 1);
                    ItemMeta meta = stack.getItemMeta();
                    if (meta != null) {
                        meta.getPersistentDataContainer().set(WAND_KEY, PersistentDataType.BYTE, (byte) 1);
                        meta.displayName(Component.text("v1 Wand", NamedTextColor.GOLD));
                        stack.setItemMeta(meta);
                    }
                    if (player.getInventory().firstEmpty() >= 0) {
                        player.getInventory().addItem(stack);
                        return;
                    }
                    player.getWorld().dropItemNaturally(player.getLocation(), stack);
                }

                @Override
                public void take(Player player, Material material) {
                    ItemStack[] contents = player.getInventory().getContents();
                    for (int i = 0; i < contents.length; i++) {
                        ItemStack stack = contents[i];
                        if (stack == null || stack.getType() != material) {
                            continue;
                        }
                        if (isWandItem(stack)) {
                            player.getInventory().setItem(i, null);
                        }
                    }
                }
            };
        }

        static boolean isWandItem(ItemStack stack) {
            if (stack == null || !stack.hasItemMeta()) {
                return false;
            }
            ItemMeta meta = stack.getItemMeta();
            return meta.getPersistentDataContainer().has(WAND_KEY, PersistentDataType.BYTE);
        }
    }
}
