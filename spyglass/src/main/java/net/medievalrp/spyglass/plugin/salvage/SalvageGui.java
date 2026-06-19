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
 * The {@code /sg inventory} GUI: three paginated, extract-only levels —
 * <b>rollbacks → containers → items</b>. Built on the raw Bukkit inventory API
 * (no extra dependency). The bottom row is a nav bar (back / prev / next);
 * the top 45 slots are content. Every click is cancelled and item movement is
 * performed manually for takes only, which blocks shift-insert, number-key
 * swaps, drags, double-click collect, and hopper pulls.
 */
public final class SalvageGui implements Listener {

    private static final int SIZE = 54;
    private static final int CONTENT = 45;        // slots 0..44
    private static final int SLOT_BACK = 45;
    private static final int SLOT_PREV = 48;
    private static final int SLOT_INFO = 49;
    private static final int SLOT_NEXT = 50;
    private static final int FETCH_CAP = 2000;    // max containers loaded per rollback
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final SalvageStore store;
    private final Executor storeExecutor;
    private final SalvageWithdrawLogger withdrawLogger;
    private final int rollbackListLimit;
    private final Logger logger;

    public SalvageGui(SalvageStore store, Executor storeExecutor,
                      SalvageWithdrawLogger withdrawLogger, int rollbackListLimit, Logger logger) {
        this.store = store;
        this.storeExecutor = storeExecutor;
        this.withdrawLogger = withdrawLogger;
        this.rollbackListLimit = rollbackListLimit;
        this.logger = logger;
    }

    // ---- level openers -------------------------------------------------

    /** Top level: one icon per rollback that has unrecovered salvage. */
    public void openRollbacks(Player player) {
        openRollbacks(player, 0);
    }

    private void openRollbacks(Player player, int page) {
        List<SalvageStore.RollbackGroup> groups = store.listRollbacks(rollbackListLimit);
        if (groups.isEmpty()) {
            player.sendMessage(Component.text("No salvaged inventories.", NamedTextColor.GRAY));
            return;
        }
        int pages = pageCount(groups.size());
        page = clamp(page, pages);
        SalvageHolder holder = SalvageHolder.rollbacks(groups, page);
        Inventory inv = Bukkit.createInventory(holder, SIZE,
                Component.text("Rollback Salvage", NamedTextColor.GOLD));
        holder.setInventory(inv);
        int base = page * CONTENT;
        for (int s = 0; s < CONTENT && base + s < groups.size(); s++) {
            inv.setItem(s, rollbackIcon(groups.get(base + s)));
        }
        navBar(inv, false, page, pages);
        player.openInventory(inv);
    }

    private void openChests(Player player, UUID rollbackId, int page) {
        List<SalvageSnapshot> snaps = store.listByRollback(rollbackId, FETCH_CAP);
        if (snaps.isEmpty()) {
            openRollbacks(player, 0); // this rollback was fully recovered
            return;
        }
        int pages = pageCount(snaps.size());
        page = clamp(page, pages);
        SalvageHolder holder = SalvageHolder.chests(rollbackId, snaps, page);
        Inventory inv = Bukkit.createInventory(holder, SIZE,
                Component.text(snaps.size() + " container(s) — by " + snaps.get(0).operatorName(),
                        NamedTextColor.GOLD));
        holder.setInventory(inv);
        int base = page * CONTENT;
        for (int s = 0; s < CONTENT && base + s < snaps.size(); s++) {
            inv.setItem(s, chestIcon(snaps.get(base + s)));
        }
        navBar(inv, true, page, pages);
        player.openInventory(inv);
    }

    private void openItems(Player player, UUID rollbackId, SalvageSnapshot snap, int page) {
        List<StoredItem> items = snap.items();
        int pages = pageCount(Math.max(1, items.size()));
        page = clamp(page, pages);
        SalvageHolder holder = SalvageHolder.items(rollbackId, snap, page);
        Inventory inv = Bukkit.createInventory(holder, SIZE,
                Component.text("Salvage " + shortId(snap.id()), NamedTextColor.GOLD));
        holder.setInventory(inv);
        int base = page * CONTENT;
        for (int s = 0; s < CONTENT && base + s < items.size(); s++) {
            ItemStack stack = ItemSerialization.decode(items.get(base + s).data());
            if (stack != null) {
                inv.setItem(s, stack);
            }
        }
        navBar(inv, true, page, pages);
        player.openInventory(inv);
    }

    // ---- click routing -------------------------------------------------

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof SalvageHolder holder)) {
            return;
        }
        event.setCancelled(true); // extract-only; takes are manual below
        Inventory top = event.getView().getTopInventory();
        if (event.getClickedInventory() != top || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        int slot = event.getSlot();
        int page = holder.page();

        // Nav bar
        if (slot >= CONTENT) {
            switch (holder.kind()) {
                case ROLLBACKS -> {
                    if (slot == SLOT_PREV) openRollbacks(player, page - 1);
                    else if (slot == SLOT_NEXT) openRollbacks(player, page + 1);
                }
                case CHESTS -> {
                    if (slot == SLOT_BACK) openRollbacks(player, 0);
                    else if (slot == SLOT_PREV) openChests(player, holder.rollbackId(), page - 1);
                    else if (slot == SLOT_NEXT) openChests(player, holder.rollbackId(), page + 1);
                }
                case ITEMS -> {
                    if (slot == SLOT_BACK) openChests(player, holder.rollbackId(), 0);
                    else if (slot == SLOT_PREV) openItems(player, holder.rollbackId(), holder.snapshot(), page - 1);
                    else if (slot == SLOT_NEXT) openItems(player, holder.rollbackId(), holder.snapshot(), page + 1);
                }
            }
            return;
        }

        // Content
        switch (holder.kind()) {
            case ROLLBACKS -> {
                int idx = page * CONTENT + slot;
                List<SalvageStore.RollbackGroup> groups = holder.rollbacks();
                if (idx < groups.size()) {
                    openChests(player, groups.get(idx).rollbackId(), 0);
                }
            }
            case CHESTS -> {
                int idx = page * CONTENT + slot;
                List<SalvageSnapshot> snaps = holder.snapshots();
                if (idx < snaps.size()) {
                    openItems(player, holder.rollbackId(), snaps.get(idx), 0);
                }
            }
            case ITEMS -> take(player, holder, slot);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof SalvageHolder) {
            event.setCancelled(true);
        }
    }

    // ---- extraction ----------------------------------------------------

    private void take(Player player, SalvageHolder holder, int slot) {
        SalvageSnapshot snap = holder.snapshot();
        List<StoredItem> items = snap.items();
        int index = holder.page() * CONTENT + slot;
        if (index < 0 || index >= items.size()) {
            return;
        }
        ItemStack stack = ItemSerialization.decode(items.get(index).data());
        if (stack == null || stack.getType() == Material.AIR) {
            return;
        }
        Map<Integer, ItemStack> overflow = player.getInventory().addItem(stack.clone());
        ItemStack remaining = overflow.isEmpty() ? null : overflow.values().iterator().next();
        int taken = stack.getAmount() - (remaining == null ? 0 : remaining.getAmount());
        if (taken <= 0) {
            player.sendMessage(Component.text("Your inventory is full.", NamedTextColor.RED));
            return;
        }
        if (withdrawLogger != null) {
            ItemStack takenStack = stack.clone();
            takenStack.setAmount(taken);
            try {
                withdrawLogger.log(player, snap, takenStack, taken);
            } catch (RuntimeException ex) {
                logger.warning("Spyglass salvage withdraw log failed: " + ex.getMessage());
            }
        }
        // Rebuild the snapshot's item list: drop the slot if emptied, else
        // replace it with the leftover.
        List<StoredItem> updated = new ArrayList<>(items);
        if (remaining == null) {
            updated.remove(index);
        } else {
            updated.set(index, ItemSerialization.storedItem(items.get(index).slot(), remaining));
        }
        UUID id = snap.id();
        storeExecutor.execute(() -> {
            try {
                if (updated.isEmpty()) {
                    store.delete(id);
                } else {
                    store.replaceItems(id, updated);
                }
            } catch (RuntimeException ex) {
                logger.warning("Spyglass salvage persist failed: " + ex.getMessage());
            }
        });
        if (updated.isEmpty()) {
            openChests(player, holder.rollbackId(), 0);
        } else {
            openItems(player, holder.rollbackId(), snap.withItems(updated), holder.page());
        }
    }

    // ---- rendering helpers --------------------------------------------

    private void navBar(Inventory inv, boolean back, int page, int pages) {
        ItemStack filler = button(Material.GRAY_STAINED_GLASS_PANE, " ", null);
        for (int s = CONTENT; s < SIZE; s++) {
            inv.setItem(s, filler);
        }
        if (back) {
            inv.setItem(SLOT_BACK, button(Material.ARROW, "Back", NamedTextColor.YELLOW));
        }
        inv.setItem(SLOT_INFO, button(Material.PAPER, "Page " + (page + 1) + " / " + pages, NamedTextColor.GRAY));
        if (page > 0) {
            inv.setItem(SLOT_PREV, button(Material.ARROW, "Previous page", NamedTextColor.GREEN));
        }
        if (page + 1 < pages) {
            inv.setItem(SLOT_NEXT, button(Material.ARROW, "Next page", NamedTextColor.GREEN));
        }
    }

    private ItemStack rollbackIcon(SalvageStore.RollbackGroup group) {
        ItemStack icon = new ItemStack(Material.CHEST_MINECART);
        ItemMeta meta = icon.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("Rollback by " + group.operatorName(), NamedTextColor.YELLOW));
            meta.lore(List.of(
                    Component.text(group.containerCount() + " salvaged container(s)", NamedTextColor.GRAY),
                    Component.text(TIME.format(group.latest()), NamedTextColor.DARK_GRAY),
                    Component.text("Click to open", NamedTextColor.GREEN)));
            icon.setItemMeta(meta);
        }
        return icon;
    }

    private ItemStack chestIcon(SalvageSnapshot snap) {
        Material material = Material.matchMaterial(snap.containerType());
        if (material == null || !material.isItem()) {
            material = Material.CHEST;
        }
        ItemStack icon = new ItemStack(material);
        ItemMeta meta = icon.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(snap.containerType() + " " + shortId(snap.id()),
                    NamedTextColor.YELLOW));
            meta.lore(List.of(
                    Component.text(snap.worldName() + " " + snap.x() + ", " + snap.y() + ", " + snap.z(),
                            NamedTextColor.GRAY),
                    Component.text(snap.items().size() + " stack(s)", NamedTextColor.GRAY),
                    Component.text("Click to open", NamedTextColor.GREEN)));
            icon.setItemMeta(meta);
        }
        return icon;
    }

    private static ItemStack button(Material material, String name, NamedTextColor color) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(name, color == null ? NamedTextColor.DARK_GRAY : color)
                    .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
            item.setItemMeta(meta);
        }
        return item;
    }

    private static int pageCount(int items) {
        return Math.max(1, (items + CONTENT - 1) / CONTENT);
    }

    private static int clamp(int page, int pages) {
        return Math.max(0, Math.min(page, pages - 1));
    }

    private static String shortId(UUID id) {
        return "#" + id.toString().substring(0, 8);
    }
}
