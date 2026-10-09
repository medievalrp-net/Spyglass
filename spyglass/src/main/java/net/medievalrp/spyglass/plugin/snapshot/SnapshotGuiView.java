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
import net.medievalrp.spyglass.api.capture.ItemSerialization;
import net.medievalrp.spyglass.plugin.gui.Cell;
import net.medievalrp.spyglass.plugin.gui.Gui;
import net.medievalrp.spyglass.plugin.gui.Menu;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * {@code /sg snapshot} viewer. A single non-paged {@link Menu} lays out either a
 * captured container (its content rows, 1:1 by slot index) or a captured
 * player inventory (armor/offhand/main/hotbar, mirroring how a player reads
 * their own inventory), plus an info cell describing the capture.
 *
 * <p>Unlike {@code SalvageGuiView}, the {@link SnapshotSession} handed to
 * {@link #open} is already fully resolved (the reconstructor or the player
 * snapshot store already ran, off-thread, before this was called) - there is
 * no further store read here, so the menu is built and opened synchronously
 * on the calling (main) thread, exactly as {@link SnapshotView#open} requires.
 *
 * <p>Every occupied slot is a click item: a click takes a <b>copy</b> into the
 * viewer's inventory (never depletes the session, since the session is a
 * read of the past, not a live container). The take itself - the permission
 * re-check, the whole-stack-or-refuse fit rule, and the audit record - is
 * {@link SnapshotTakes#take}. {@link Gui} menus cancel every interaction,
 * so the GUI is inherently extract-only.
 */
final class SnapshotGuiView implements SnapshotView {

    private static final int WIDTH = 9;

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    // 36-39: boots, leggings, chestplate, helmet (CraftBukkit's armor-array
    // order), matching how PlayerInventory#getContents() lays them out and
    // therefore how PlayerSnapshotService captured them.
    private static final String[] ARMOR_LABELS = {"Boots", "Leggings", "Chestplate", "Helmet"};

    private final Gui gui;
    private final SnapshotTakes takes;
    private final SnapshotSessions sessions;
    private final Logger logger;

    SnapshotGuiView(Gui gui, SnapshotTakes takes, SnapshotSessions sessions, Logger logger) {
        this.gui = gui;
        this.takes = takes;
        this.sessions = sessions;
        this.logger = logger;
    }

    @Override
    public void open(Player viewer, SnapshotSession session) {
        if (session.kind() == SnapshotSession.Kind.PLAYER) {
            openPlayer(viewer, session);
        } else {
            openContainer(viewer, session);
        }
    }

    // ---- menu builders ---------------------------------------------------

    private void openContainer(Player viewer, SnapshotSession session) {
        // A chest window is capped at 6 rows. A 54-slot session (double
        // chest, or a destroyed container's assumed size) already fills all
        // six, so the info row that smaller sessions get would push the
        // window to 63 cells and Bukkit refuses to build it (#351) - the
        // player used to get nothing at all. Those sessions keep their 1:1
        // layout and the info moves to chat instead.
        int rows = Math.min(6, Math.max(1, session.containerRows()));
        boolean infoRow = rows < 6;
        int height = infoRow ? rows + 1 : rows;
        Map<Integer, Cell> cells = filled(height);

        Map<Integer, SnapshotSlot> bySlot = indexBySlot(session);
        int contentCells = rows * WIDTH;
        for (int slot = 0; slot < contentCells; slot++) {
            SnapshotSlot snapshotSlot = bySlot.get(slot);
            if (snapshotSlot != null) {
                cells.put(slot, contentItem(viewer, session, snapshotSlot));
            }
        }
        if (infoRow) {
            // Bottom row, centered: filler already covers it, overlay the info cell.
            cells.put(contentCells + 4, infoItem(session));
        }

        open(viewer, height, cells, session);
        if (!infoRow) {
            sendInfoToChat(viewer, session);
        }
    }

    /** The info the missing book would have carried, for 6-row sessions. */
    private static void sendInfoToChat(Player viewer, SnapshotSession session) {
        long agoSeconds = Math.max(0L,
                Instant.now().getEpochSecond() - session.asOf().getEpochSecond());
        viewer.sendMessage(net.medievalrp.spyglass.plugin.command.render.Feedback.info(
                session.subjectLabel() + " as of " + new Duration(agoSeconds).compact() + " ago"));
        for (String note : session.notes()) {
            viewer.sendMessage(net.medievalrp.spyglass.plugin.command.render.Feedback.bonus(
                    "- " + note));
        }
    }

    private void openPlayer(Player viewer, SnapshotSession session) {
        int height = 6;
        Map<Integer, Cell> cells = filled(height);

        Map<Integer, SnapshotSlot> bySlot = indexBySlot(session);
        Cell info = infoItem(session);

        // Row 0: armor (cols 0-3, slots 36-39), offhand (col 5, slot 40), info (col 8).
        for (int col = 0; col < ARMOR_LABELS.length; col++) {
            int invSlot = 36 + col;
            cells.put(col, armorOrOffhandItem(viewer, session, bySlot.get(invSlot), ARMOR_LABELS[col]));
        }
        cells.put(5, armorOrOffhandItem(viewer, session, bySlot.get(40), "Off Hand"));
        cells.put(8, info);

        // Rows 1-3: main inventory, slots 9-35 (27 slots), 1:1 in reading order.
        for (int i = 0; i < 27; i++) {
            int invSlot = 9 + i;
            SnapshotSlot snapshotSlot = bySlot.get(invSlot);
            if (snapshotSlot != null) {
                cells.put(WIDTH + i, contentItem(viewer, session, snapshotSlot));
            }
        }

        // Row 4: hotbar, slots 0-8.
        for (int col = 0; col < WIDTH; col++) {
            SnapshotSlot snapshotSlot = bySlot.get(col);
            if (snapshotSlot != null) {
                cells.put(4 * WIDTH + col, contentItem(viewer, session, snapshotSlot));
            }
        }

        // Row 5: filler, plus a second info cell in the same column as row 0's
        // (the nav-row convention the salvage menus use too).
        cells.put(5 * WIDTH + 8, info);

        open(viewer, height, cells, session);
    }

    private void open(Player viewer, int rows, Map<Integer, Cell> cells, SnapshotSession session) {
        gui.open(viewer, new Menu(Component.text(session.subjectLabel()), rows, cells));
    }

    private static Map<Integer, Cell> filled(int rows) {
        Map<Integer, Cell> cells = new HashMap<>();
        Cell filler = Cell.icon(fillerIcon());
        int count = rows * WIDTH;
        for (int i = 0; i < count; i++) {
            cells.put(i, filler);
        }
        return cells;
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
    private Cell contentItem(Player viewer, SnapshotSession session, SnapshotSlot slot) {
        ItemStack display = decodeDisplay(session, slot);
        if (display == null) {
            return Cell.icon(barrierIcon(slot));
        }
        return slotItem(viewer, session, slot, display);
    }

    /**
     * An armor or offhand cell. Occupied slots render the real item with an
     * extra lore line naming the slot (so the layout reads even out of
     * position); the label is display-only and is never part of what a take
     * actually gives the player. Empty slots render a labeled placeholder so
     * the layout still reads as an inventory.
     */
    private Cell armorOrOffhandItem(Player viewer, SnapshotSession session, SnapshotSlot slot, String label) {
        if (slot == null) {
            return Cell.icon(labeledPlaceholder(label));
        }
        ItemStack pristine = decodeDisplay(session, slot);
        if (pristine == null) {
            ItemStack icon = barrierIcon(slot);
            appendLore(icon, label);
            return Cell.icon(icon);
        }
        ItemStack labeledDisplay = pristine.clone();
        appendLore(labeledDisplay, label);
        return slotItem(viewer, session, slot, labeledDisplay);
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
     * no in-flight tracker, unlike the salvage withdraw path.
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

    private Cell infoItem(SnapshotSession session) {
        return Cell.icon(infoIcon(session));
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
                // No confidence label. A reconstruction that reconciled has
                // nothing to say and says nothing; one that did not lists the
                // specific reasons below, which is the whole signal. Matches
                // the chat listing, which never labelled the clean case.
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
    private Cell slotItem(Player viewer, SnapshotSession session, SnapshotSlot slot, ItemStack display) {
        return new Cell(display, who -> {
            // The token path validates every take against the live session
            // cache; this menu used to skip that, so it kept handing out
            // copies past the 15-minute TTL and after a newer snapshot had
            // superseded it. Same gate, same wording, and the dead menu
            // closes so it cannot mislead again.
            if (sessions.resolve(who, session.token()).isEmpty()) {
                who.sendMessage(Component.text(
                        "That snapshot has expired - re-run /sg snapshot.", NamedTextColor.RED));
                gui.nextTick(who::closeInventory);
                return;
            }
            takeCopy(viewer, session, slot);
        });
    }
}
