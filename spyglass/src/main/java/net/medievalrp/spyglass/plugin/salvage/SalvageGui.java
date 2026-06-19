package net.medievalrp.spyglass.plugin.salvage;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.medievalrp.spyglass.api.event.StoredItem;
import net.medievalrp.spyglass.plugin.util.ItemSerialization;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * The {@code /sg inventory} GUI: a grid of chest icons (one per destroyed
 * inventory) that opens into the salvaged items, <b>extract-only</b>. Built on
 * the raw Bukkit inventory API — no extra dependency. Every click is cancelled
 * by default and item movement is performed manually for takes only, which
 * blocks shift-insert, number-key swaps, drags, double-click collect, and
 * hopper pulls in one stroke.
 */
public final class SalvageGui implements Listener {

    private static final int MAX_SLOTS = 54;
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final SalvageStore store;
    private final Executor storeExecutor;
    private final SalvageWithdrawLogger withdrawLogger;
    private final int listLimit;
    private final Logger logger;

    public SalvageGui(SalvageStore store, Executor storeExecutor,
                      SalvageWithdrawLogger withdrawLogger, int listLimit, Logger logger) {
        this.store = store;
        this.storeExecutor = storeExecutor;
        this.withdrawLogger = withdrawLogger;
        this.listLimit = listLimit;
        this.logger = logger;
    }

    /** The chest-icon grid of every salvaged inventory, newest first. */
    public void openIndex(Player player) {
        List<SalvageSnapshot> snaps = store.list(listLimit);
        if (snaps.isEmpty()) {
            player.sendMessage(Component.text("No salvaged inventories.", NamedTextColor.GRAY));
            return;
        }
        int size = clampSize(snaps.size());
        SalvageHolder holder = SalvageHolder.index(snaps);
        Inventory inv = Bukkit.createInventory(holder, size,
                Component.text("Rollback Salvage", NamedTextColor.GOLD));
        holder.setInventory(inv);
        for (int i = 0; i < snaps.size() && i < size; i++) {
            inv.setItem(i, indexIcon(snaps.get(i)));
        }
        player.openInventory(inv);
    }

    /** One snapshot's salvaged items, take-only. */
    public void openSnapshot(Player player, SalvageSnapshot snap) {
        List<StoredItem> items = snap.items();
        int size = clampSize(Math.max(1, items.size()));
        SalvageHolder holder = SalvageHolder.snapshot(snap);
        Inventory inv = Bukkit.createInventory(holder, size,
                Component.text("Salvage " + shortId(snap.id()), NamedTextColor.GOLD));
        holder.setInventory(inv);
        for (int i = 0; i < items.size() && i < size; i++) {
            ItemStack stack = ItemSerialization.decode(items.get(i).data());
            if (stack != null) {
                inv.setItem(i, stack);
            }
        }
        player.openInventory(inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof SalvageHolder holder)) {
            return;
        }
        // Extract-only: deny every default movement; takes are done manually.
        event.setCancelled(true);
        Inventory top = event.getView().getTopInventory();
        if (event.getClickedInventory() != top || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        int slot = event.getSlot();
        if (holder.kind() == SalvageHolder.Kind.INDEX) {
            List<SalvageSnapshot> snaps = holder.snapshots();
            if (slot >= 0 && slot < snaps.size()) {
                openSnapshot(player, snaps.get(slot));
            }
            return;
        }
        take(player, holder, top, slot);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof SalvageHolder) {
            event.setCancelled(true);
        }
    }

    private void take(Player player, SalvageHolder holder, Inventory top, int slot) {
        ItemStack item = top.getItem(slot);
        if (item == null || item.getType() == Material.AIR) {
            return;
        }
        Map<Integer, ItemStack> overflow = player.getInventory().addItem(item.clone());
        ItemStack remaining = overflow.isEmpty() ? null : overflow.values().iterator().next();
        int taken = item.getAmount() - (remaining == null ? 0 : remaining.getAmount());
        if (taken <= 0) {
            player.sendMessage(Component.text("Your inventory is full.", NamedTextColor.RED));
            return;
        }
        top.setItem(slot, remaining);
        if (withdrawLogger != null && holder.snapshot() != null) {
            ItemStack takenStack = item.clone();
            takenStack.setAmount(taken);
            try {
                withdrawLogger.log(player, holder.snapshot(), takenStack, taken);
            } catch (RuntimeException ex) {
                logger.warning("Spyglass salvage withdraw log failed: " + ex.getMessage());
            }
        }
        persist(holder, top);
    }

    // The GUI is the source of truth once open: recompute the snapshot's
    // remaining items from its current contents and write that back off-main.
    private void persist(SalvageHolder holder, Inventory top) {
        SalvageSnapshot snap = holder.snapshot();
        if (snap == null) {
            return;
        }
        List<StoredItem> remaining = new ArrayList<>();
        for (int i = 0; i < top.getSize(); i++) {
            ItemStack stack = top.getItem(i);
            if (stack != null && stack.getType() != Material.AIR) {
                remaining.add(ItemSerialization.storedItem(i, stack));
            }
        }
        UUID id = snap.id();
        storeExecutor.execute(() -> {
            try {
                if (remaining.isEmpty()) {
                    store.delete(id);
                } else {
                    store.replaceItems(id, remaining);
                }
            } catch (RuntimeException ex) {
                logger.warning("Spyglass salvage persist failed: " + ex.getMessage());
            }
        });
    }

    private ItemStack indexIcon(SalvageSnapshot snap) {
        Material material = Material.matchMaterial(snap.containerType());
        if (material == null || !material.isItem()) {
            material = Material.CHEST;
        }
        ItemStack icon = new ItemStack(material);
        ItemMeta meta = icon.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("Salvage " + shortId(snap.id()), NamedTextColor.YELLOW));
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text(snap.worldName() + " " + snap.x() + ", " + snap.y() + ", " + snap.z(),
                    NamedTextColor.GRAY));
            lore.add(Component.text(snap.items().size() + " stack(s)", NamedTextColor.GRAY));
            lore.add(Component.text("by " + snap.operatorName(), NamedTextColor.DARK_GRAY));
            lore.add(Component.text(TIME.format(snap.capturedAt()), NamedTextColor.DARK_GRAY));
            lore.add(Component.text("Click to open", NamedTextColor.GREEN));
            meta.lore(lore);
            icon.setItemMeta(meta);
        }
        return icon;
    }

    private static int clampSize(int itemCount) {
        int rows = (Math.min(itemCount, MAX_SLOTS) + 8) / 9;
        return Math.max(1, Math.min(6, rows)) * 9;
    }

    private static String shortId(UUID id) {
        return "#" + id.toString().substring(0, 8);
    }
}
