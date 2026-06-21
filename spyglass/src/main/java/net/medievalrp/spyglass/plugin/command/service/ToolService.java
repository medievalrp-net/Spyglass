package net.medievalrp.spyglass.plugin.command.service;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.medievalrp.spyglass.plugin.command.render.Feedback;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
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
 * {@code /spyglass tool} calls follow the same UX muscle memory:
 * <ol>
 *   <li>Inactive → activate + give/move the wand, say "Activated".</li>
 *   <li>Active but wand missing from inventory → add it, say "Added".</li>
 *   <li>Active with wand in inventory but not in main hand → swap to hand,
 *       say "Moved".</li>
 *   <li>Active with wand already in main hand → deactivate, say "Deactivated".</li>
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
    private final ServiceSupport support;
    private final Logger logger;
    private final Set<UUID> active = ConcurrentHashMap.newKeySet();

    /** Production entry: store writes are persisted off the main thread. */
    public ToolService(ToolStateStore store, Material wandMaterial, ServiceSupport support, Logger logger) {
        this(store, wandMaterial, WandHandout.bukkit(), support, logger);
    }

    // Test convenience: persists synchronously so verify(store).enable(...)
    // observes the write inline, no scheduler needed.
    public ToolService(ToolStateStore store, Material wandMaterial, WandHandout handout) {
        this(store, wandMaterial, handout, ServiceSupport.synchronous(), Logger.getLogger("spyglass-tool-test"));
    }

    public ToolService(ToolStateStore store, Material wandMaterial, WandHandout handout,
                       ServiceSupport support, Logger logger) {
        this.store = store;
        this.wandMaterial = wandMaterial;
        this.handout = handout;
        this.support = support;
        this.logger = logger;
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
                player.sendMessage(Feedback.toolOk("Added the Spyglass data tool to your inventory."));
                return;
            }
            if (!inHand) {
                swapToMainHand(inv, slot);
                player.sendMessage(Feedback.toolOk("Moved the data tool to your hand."));
                return;
            }
            active.remove(id);
            persistAsync(() -> store.disable(id), "disable");
            player.sendMessage(Feedback.toolOk("Deactivated the Spyglass Data Tool"));
            return;
        }

        // Inactive → activate. If the wand isn't in inventory, just add it;
        // if it's present but not in hand, swap it in silently. Either way,
        // the chat message mirrors v1 exactly.
        active.add(id);
        persistAsync(() -> store.enable(id), "enable");
        if (slot == -1) {
            handout.give(player, wandMaterial);
            player.sendMessage(Feedback.toolOk("Added the Spyglass data tool to your inventory."));
            return;
        }
        if (!inHand) {
            swapToMainHand(inv, slot);
        }
        player.sendMessage(Component.text()
                .append(Component.text("Activated the Spyglass Data Tool ", NamedTextColor.GREEN))
                .append(Component.text("(" + wandMaterial.name() + ")", NamedTextColor.GRAY))
                .asComponent());
    }

    // Tool state is persisted off the main thread. The in-memory `active`
    // set is the source of truth for behavior this session, so the toggle
    // responds instantly; the store write (Mongo deleteOne/replaceOne, a
    // SQLite write-lock, or a ClickHouse future the driver blocks on for up
    // to 30s) must never sit on the tick. Fire-and-forget with structured
    // logging on failure, per ServiceSupport.onAsyncThread's contract.
    private void persistAsync(Runnable op, String what) {
        support.onAsyncThread(() -> {
            try {
                op.run();
            } catch (RuntimeException ex) {
                logger.warning("Spyglass tool-state " + what + " persist failed: " + ex.getMessage());
            }
        });
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
                        meta.displayName(Component.text("Spyglass Wand", NamedTextColor.GOLD).decorate(TextDecoration.BOLD));
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
