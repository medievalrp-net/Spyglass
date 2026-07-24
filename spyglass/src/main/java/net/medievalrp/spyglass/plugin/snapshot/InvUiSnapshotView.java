package net.medievalrp.spyglass.plugin.snapshot;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.medievalrp.spyglass.api.event.StoredItem;
import net.medievalrp.spyglass.api.util.Duration;
import net.medievalrp.spyglass.plugin.util.ItemSerialization;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import xyz.xenondevs.inventoryaccess.component.AdventureComponentWrapper;
import xyz.xenondevs.invui.InvUI;
import xyz.xenondevs.invui.gui.Gui;
import xyz.xenondevs.invui.item.Item;
import xyz.xenondevs.invui.item.ItemProvider;
import xyz.xenondevs.invui.item.ItemWrapper;
import xyz.xenondevs.invui.item.impl.AbstractItem;
import xyz.xenondevs.invui.item.impl.SimpleItem;
import xyz.xenondevs.invui.window.Window;

/**
 * InvUI-backed {@code /sg snapshot} viewer for the Minecraft versions InvUI
 * 1.49 supports (1.x). A single non-paged {@link Gui} lays out either a
 * captured container (its content rows, 1:1 by slot index) or a captured
 * player inventory (armor/offhand/main/hotbar, mirroring how a player reads
 * their own inventory), plus an info cell describing the capture. On versions
 * InvUI does not support (26.x) there is no GUI at all (see
 * {@link SnapshotViews}), matching the salvage split exactly.
 *
 * <p>Unlike {@code InvUiSalvageView}, the {@link SnapshotSession} handed to
 * {@link #open} is already fully resolved (the reconstructor or the player
 * snapshot store already ran, off-thread, before this was called) - there is
 * no further store read here, so the window is built and opened synchronously
 * on the calling (main) thread, exactly as {@link SnapshotView#open} requires.
 *
 * <p>Every occupied slot is a click item: a click takes a <b>copy</b> into the
 * viewer's inventory (never depletes the session, since the session is a
 * read of the past, not a live container). The take itself - the permission
 * re-check, the whole-stack-or-refuse fit rule, and the audit record - is
 * {@link SnapshotTakes#take}, the exact engine the text-fallback
 * {@code /sg snapshot take <token> <slot>} command calls, so the two surfaces
 * cannot drift apart (the {@code SalvageWithdrawals} precedent). InvUI content
 * slots are click-cancelled by default (see {@code InvUiSalvageView}'s
 * javadoc), so the GUI is inherently extract-only.
 *
 * <p>This class is only instantiated on supported versions (see
 * {@link SnapshotViews}), so a 26.x server never loads any InvUI class.
 */
final class InvUiSnapshotView implements SnapshotView {

    private static final int WIDTH = 9;

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    // 36-39: boots, leggings, chestplate, helmet (CraftBukkit's armor-array
    // order), matching how PlayerInventory#getContents() lays them out and
    // therefore how PlayerSnapshotService captured them.
    private static final String[] ARMOR_LABELS = {"Boots", "Leggings", "Chestplate", "Helmet"};

    private final SnapshotTakes takes;
    private final Logger logger;

    InvUiSnapshotView(Plugin plugin, SnapshotTakes takes, Logger logger) {
        this.takes = takes;
        this.logger = logger;
        // InvUI resolves its scheduler/listeners from the owning plugin; must be
        // set before any Window is built. The singleton accepts exactly one
        // setPlugin for the plugin's lifetime, and InvUiSalvageView (constructed
        // earlier in onEnable) has usually claimed it already with the same
        // plugin - that state is expected, not an error (caught live on the
        // first #341 boot; unit tests cannot reach the relocated singleton).
        try {
            InvUI.getInstance().setPlugin(plugin);
        } catch (IllegalStateException alreadySet) {
            // Same plugin either way; nothing to do.
        }
    }

    @Override
    public void open(Player viewer, SnapshotSession session) {
        if (session.kind() == SnapshotSession.Kind.PLAYER) {
            openPlayer(viewer, session);
        } else {
            openContainer(viewer, session);
        }
    }

    // ---- window builders -------------------------------------------------

    private void openContainer(Player viewer, SnapshotSession session) {
        int rows = Math.max(1, session.containerRows());
        int height = rows + 1;
        Gui gui = Gui.empty(WIDTH, height);
        fillAll(gui, height);

        Map<Integer, SnapshotSlot> bySlot = indexBySlot(session);
        int contentCells = rows * WIDTH;
        for (int slot = 0; slot < contentCells; slot++) {
            SnapshotSlot snapshotSlot = bySlot.get(slot);
            if (snapshotSlot != null) {
                gui.setItem(slot, contentItem(viewer, session, snapshotSlot));
            }
        }
        // Bottom row, centered: filler already covers it, overlay the info cell.
        gui.setItem(contentCells + 4, infoItem(session));

        openWindow(viewer, gui, session);
    }

    private void openPlayer(Player viewer, SnapshotSession session) {
        int height = 6;
        Gui gui = Gui.empty(WIDTH, height);
        fillAll(gui, height);

        Map<Integer, SnapshotSlot> bySlot = indexBySlot(session);
        Item info = infoItem(session);

        // Row 0: armor (cols 0-3, slots 36-39), offhand (col 5, slot 40), info (col 8).
        for (int col = 0; col < ARMOR_LABELS.length; col++) {
            int invSlot = 36 + col;
            gui.setItem(col, armorOrOffhandItem(viewer, session, bySlot.get(invSlot), ARMOR_LABELS[col]));
        }
        gui.setItem(5, armorOrOffhandItem(viewer, session, bySlot.get(40), "Off Hand"));
        gui.setItem(8, info);

        // Rows 1-3: main inventory, slots 9-35 (27 slots), 1:1 in reading order.
        for (int i = 0; i < 27; i++) {
            int invSlot = 9 + i;
            SnapshotSlot snapshotSlot = bySlot.get(invSlot);
            if (snapshotSlot != null) {
                gui.setItem(WIDTH + i, contentItem(viewer, session, snapshotSlot));
            }
        }

        // Row 4: hotbar, slots 0-8.
        for (int col = 0; col < WIDTH; col++) {
            SnapshotSlot snapshotSlot = bySlot.get(col);
            if (snapshotSlot != null) {
                gui.setItem(4 * WIDTH + col, contentItem(viewer, session, snapshotSlot));
            }
        }

        // Row 5: filler, plus a second info cell in the same column as row 0's
        // (the nav-row convention every other InvUI view in this codebase uses).
        gui.setItem(5 * WIDTH + 8, info);

        openWindow(viewer, gui, session);
    }

    private void openWindow(Player viewer, Gui gui, SnapshotSession session) {
        Window.single()
                .setViewer(viewer)
                .setTitle(new AdventureComponentWrapper(
                        Component.text(session.subjectLabel(), NamedTextColor.GOLD)))
                .setGui(gui)
                .build()
                .open();
    }

    private static void fillAll(Gui gui, int rows) {
        Item filler = new SimpleItem(new ItemWrapper(fillerIcon()));
        int cells = rows * WIDTH;
        for (int i = 0; i < cells; i++) {
            gui.setItem(i, filler);
        }
    }

    private static Map<Integer, SnapshotSlot> indexBySlot(SnapshotSession session) {
        Map<Integer, SnapshotSlot> bySlot = new HashMap<>();
        for (SnapshotSlot slot : session.slots()) {
            bySlot.put(slot.slot(), slot);
        }
        return bySlot;
    }

    // ---- content rendering -------------------------------------------------

    /** A plain content cell (container slot, or player main/hotbar slot). */
    private Item contentItem(Player viewer, SnapshotSession session, SnapshotSlot slot) {
        ItemStack display = decodeDisplay(session, slot);
        if (display == null) {
            return new SimpleItem(new ItemWrapper(barrierIcon(slot)));
        }
        return new SlotItem(viewer, session, slot, display);
    }

    /**
     * An armor or offhand cell. Occupied slots render the real item with an
     * extra lore line naming the slot (so the layout reads even out of
     * position); the label is display-only and is never part of what a take
     * actually gives the player. Empty slots render a labeled placeholder so
     * the layout still reads as an inventory.
     */
    private Item armorOrOffhandItem(Player viewer, SnapshotSession session, SnapshotSlot slot, String label) {
        if (slot == null) {
            return new SimpleItem(new ItemWrapper(labeledPlaceholder(label)));
        }
        ItemStack pristine = decodeDisplay(session, slot);
        if (pristine == null) {
            ItemStack icon = barrierIcon(slot);
            appendLore(icon, label);
            return new SimpleItem(new ItemWrapper(icon));
        }
        ItemStack labeledDisplay = pristine.clone();
        appendLore(labeledDisplay, label);
        return new SlotItem(viewer, session, slot, labeledDisplay);
    }

    /**
     * Decode a slot's stored blob into the exact stack a take would give.
     * Player-mode blobs are interned at amount 1 ({@link SnapshotSlot}
     * javadoc), so the real amount is restored from {@code slot.count()};
     * container-mode blobs already carry their true amount
     * ({@link SnapshotReconstructor#COUNT_IN_BLOB}), so {@code count} must
     * <b>not</b> be applied there. Returns {@code null} on a missing or
     * corrupt blob - the caller renders a barrier fallback instead of
     * crashing the GUI open.
     */
    private ItemStack decodeDisplay(SnapshotSession session, SnapshotSlot slot) {
        StoredItem item = slot.item();
        if (item == null || item.data() == null) {
            return null;
        }
        ItemStack decoded;
        try {
            decoded = ItemSerialization.decode(item.data());
        } catch (RuntimeException ex) {
            logger.warning("Spyglass snapshot view: could not decode slot " + slot.slot()
                    + " (" + item.material() + "): " + ex.getMessage());
            return null;
        }
        if (decoded == null || decoded.getType() == Material.AIR) {
            return null;
        }
        if (session.kind() == SnapshotSession.Kind.PLAYER) {
            decoded.setAmount(Math.max(1, slot.count()));
        }
        return decoded;
    }

    /**
     * Delegate a click to the shared take engine and translate the result
     * into feedback. Slots stay populated after a take (this reads a past
     * instant, not a live container) - the session never depletes and needs
     * no in-flight tracker, unlike the salvage withdraw path. Wording matches
     * the text-fallback path in {@code SnapshotService}.
     */
    private void takeCopy(Player viewer, SnapshotSession session, SnapshotSlot slot) {
        SnapshotTakes.Result result = takes.take(viewer, session, slot.slot());
        switch (result) {
            case TAKEN -> {
                // The audit record is the receipt; no chat spam per click.
            }
            case INVENTORY_FULL -> viewer.sendMessage(Component.text(
                    "That whole stack won't fit in your inventory - nothing taken.", NamedTextColor.YELLOW));
            case NO_PERMISSION -> viewer.sendMessage(Component.text(
                    "You don't have permission to take from snapshots.", NamedTextColor.RED));
            case SLOT_EMPTY -> viewer.sendMessage(Component.text(
                    "Nothing to take in that slot.", NamedTextColor.RED));
        }
    }

    // ---- icons ---------------------------------------------------------

    private Item infoItem(SnapshotSession session) {
        return new SimpleItem(new ItemWrapper(infoIcon(session)));
    }

    private static ItemStack infoIcon(SnapshotSession session) {
        ItemStack icon = new ItemStack(Material.BOOK);
        ItemMeta meta = icon.getItemMeta();
        if (meta != null) {
            meta.displayName(name(session.subjectLabel(), NamedTextColor.GOLD));
            List<Component> lore = new ArrayList<>();
            long agoSeconds = Math.max(0L,
                    Instant.now().getEpochSecond() - session.asOf().getEpochSecond());
            lore.add(line("as of " + new Duration(agoSeconds).compact() + " ago", NamedTextColor.GRAY));
            if (session.kind() == SnapshotSession.Kind.PLAYER) {
                lore.add(line("Captured " + TIME.format(session.capturedAt()), NamedTextColor.DARK_GRAY));
                String cause = session.cause();
                if (cause != null && !cause.isBlank()) {
                    lore.add(line("Cause: " + humanize(cause), NamedTextColor.DARK_GRAY));
                }
            } else {
                boolean certain = session.certainty() == SnapshotSession.Certainty.CERTAIN;
                lore.add(line(certain ? "CERTAIN" : "UNCERTAIN",
                        certain ? NamedTextColor.GREEN : NamedTextColor.GOLD));
                for (String note : session.notes()) {
                    lore.add(line(note, NamedTextColor.DARK_GRAY));
                }
            }
            meta.lore(lore);
            icon.setItemMeta(meta);
        }
        return icon;
    }

    private static ItemStack barrierIcon(SnapshotSlot slot) {
        ItemStack icon = new ItemStack(Material.BARRIER);
        ItemMeta meta = icon.getItemMeta();
        if (meta != null) {
            meta.displayName(name("Could not decode", NamedTextColor.RED));
            StoredItem item = slot.item();
            String material = item != null && item.material() != null ? item.material() : "unknown";
            meta.lore(List.of(
                    line("Material: " + material, NamedTextColor.GRAY),
                    line("The stored data for this slot is corrupt.", NamedTextColor.DARK_GRAY)));
            icon.setItemMeta(meta);
        }
        return icon;
    }

    private static ItemStack fillerIcon() {
        ItemStack icon = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = icon.getItemMeta();
        if (meta != null) {
            meta.displayName(name(" ", NamedTextColor.GRAY));
            icon.setItemMeta(meta);
        }
        return icon;
    }

    private static ItemStack labeledPlaceholder(String label) {
        ItemStack icon = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = icon.getItemMeta();
        if (meta != null) {
            meta.displayName(name(label, NamedTextColor.DARK_GRAY));
            meta.lore(List.of(line("(empty)", NamedTextColor.DARK_GRAY)));
            icon.setItemMeta(meta);
        }
        return icon;
    }

    private static void appendLore(ItemStack stack, String label) {
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return;
        }
        List<Component> lore = meta.hasLore() && meta.lore() != null
                ? new ArrayList<>(meta.lore())
                : new ArrayList<>();
        lore.add(line(label, NamedTextColor.DARK_GRAY));
        meta.lore(lore);
        stack.setItemMeta(meta);
    }

    private static String humanize(String cause) {
        String spaced = cause.replace('-', ' ').replace('_', ' ');
        if (spaced.isEmpty()) {
            return spaced;
        }
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    private static Component name(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }

    private static Component line(String text, NamedTextColor color) {
        return Component.text(text, color);
    }

    // ---- items ---------------------------------------------------------

    /** One occupied content slot: click takes a copy (see {@link #takeCopy}). */
    private final class SlotItem extends AbstractItem {
        private final Player viewer;
        private final SnapshotSession session;
        private final SnapshotSlot slot;
        private final ItemStack display;

        SlotItem(Player viewer, SnapshotSession session, SnapshotSlot slot, ItemStack display) {
            this.viewer = viewer;
            this.session = session;
            this.slot = slot;
            this.display = display;
        }

        @Override
        public ItemProvider getItemProvider() {
            return new ItemWrapper(display.clone());
        }

        @Override
        public void handleClick(ClickType clickType, Player who, InventoryClickEvent event) {
            takeCopy(viewer, session, slot);
        }
    }
}
