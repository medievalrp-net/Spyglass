package net.medievalrp.spyglass.plugin.salvage;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.medievalrp.spyglass.api.event.StoredItem;
import net.medievalrp.spyglass.plugin.util.ItemSerialization;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import xyz.xenondevs.inventoryaccess.component.AdventureComponentWrapper;
import xyz.xenondevs.invui.InvUI;
import xyz.xenondevs.invui.gui.PagedGui;
import xyz.xenondevs.invui.gui.structure.Markers;
import xyz.xenondevs.invui.item.Item;
import xyz.xenondevs.invui.item.ItemProvider;
import xyz.xenondevs.invui.item.ItemWrapper;
import xyz.xenondevs.invui.item.impl.AbstractItem;
import xyz.xenondevs.invui.item.impl.controlitem.PageItem;
import xyz.xenondevs.invui.window.Window;

/**
 * InvUI-backed {@code /sg inventory} salvage browser for the Minecraft versions
 * InvUI 1.49 supports (1.x): three extract-only levels (rollbacks -> containers
 * -> items) with pagination, window management, and click-safety from InvUI
 * instead of hand-rolled Bukkit inventory handling. On versions InvUI does not
 * support (26.x), there is no GUI at all - salvage is command-only (see
 * {@link SalvageViews} and {@code SalvageService}).
 *
 * <p>The extract path goes through the shared {@link SalvageWithdrawals}
 * (dupe-guarded, shared with the command path), re-reads filter in-flight slots
 * ({@link InFlightTracker}), store reads run off the main thread and the window
 * opens back on it, and every take is logged. InvUI content slots are
 * click-cancelled by default, so the GUI is inherently extract-only (no inserts,
 * shift-clicks, number-key swaps, drags, or hopper pulls move items).
 *
 * <p>This class is only instantiated on supported versions (see
 * {@link SalvageViews}), so a 26.x server never loads any InvUI class.
 */
final class InvUiSalvageView implements SalvageView {

    private static final int FETCH_CAP = 2000;

    // 5 content rows (45 slots) + a nav row; 'x' = content list, '#' = filler,
    // '<'/'>' = prev/next page, 'i' = info, 'b' = back to the parent level.
    private static final String[] STRUCTURE_ROOT = {
            "x x x x x x x x x",
            "x x x x x x x x x",
            "x x x x x x x x x",
            "x x x x x x x x x",
            "x x x x x x x x x",
            "# # # < i > # # #"
    };
    private static final String[] STRUCTURE_NESTED = {
            "x x x x x x x x x",
            "x x x x x x x x x",
            "x x x x x x x x x",
            "x x x x x x x x x",
            "x x x x x x x x x",
            "b # # < i > # # #"
    };

    private final SalvageStore store;
    private final Executor storeExecutor;
    private final Executor mainExecutor;
    private final int rollbackListLimit;
    private final Logger logger;
    private final SalvageWithdrawals withdrawals;
    private final InFlightTracker inFlight;

    InvUiSalvageView(Plugin plugin, SalvageStore store, Executor storeExecutor, Executor mainExecutor,
                     SalvageWithdrawals withdrawals, int rollbackListLimit, Logger logger) {
        this.store = store;
        this.storeExecutor = storeExecutor;
        this.mainExecutor = mainExecutor;
        this.rollbackListLimit = rollbackListLimit;
        this.logger = logger;
        this.withdrawals = withdrawals;
        // Shared with the command path so a GUI take and a command take on the
        // same snapshot see each other's in-flight slots.
        this.inFlight = withdrawals.inFlight();
        // InvUI resolves its scheduler/listeners from the owning plugin; must be
        // set before any Window is built.
        InvUI.getInstance().setPlugin(plugin);
    }

    @Override
    public void open(Player player) {
        openRollbacks(player);
    }

    // ---- level openers -------------------------------------------------

    private void openRollbacks(Player player) {
        readThenOpen(() -> store.listRollbacks(rollbackListLimit),
                groups -> showRollbacks(player, groups));
    }

    private void showRollbacks(Player player, List<SalvageStore.RollbackGroup> groups) {
        if (!player.isOnline()) {
            return;
        }
        if (groups.isEmpty()) {
            player.sendMessage(Component.text("No salvaged inventories.", NamedTextColor.GRAY));
            return;
        }
        List<Item> content = new ArrayList<>(groups.size());
        for (SalvageStore.RollbackGroup group : groups) {
            content.add(new RollbackItem(player, group));
        }
        openWindow(player, STRUCTURE_ROOT, content,
                Component.text("Rollback Salvage", NamedTextColor.GOLD));
    }

    private void openChests(Player player, UUID rollbackId) {
        readThenOpen(() -> store.listByRollback(rollbackId, FETCH_CAP),
                snaps -> showChests(player, rollbackId, snaps));
    }

    private void showChests(Player player, UUID rollbackId, List<SalvageSnapshot> snaps) {
        if (!player.isOnline()) {
            return;
        }
        // Filter in-flight slots from each re-read so a stale DB result cannot
        // surface an already-taken item during the async-write window.
        List<SalvageSnapshot> visible = new ArrayList<>(snaps.size());
        for (SalvageSnapshot snap : snaps) {
            List<StoredItem> items = snap.items().stream()
                    .filter(item -> !inFlight.isInFlight(snap.id(), item.slot()))
                    .toList();
            if (!items.isEmpty()) {
                visible.add(snap.withItems(items));
            }
        }
        if (visible.isEmpty()) {
            openRollbacks(player); // this rollback was fully recovered
            return;
        }
        List<Item> content = new ArrayList<>(visible.size());
        for (SalvageSnapshot snap : visible) {
            content.add(new ChestItem(player, rollbackId, snap));
        }
        openWindow(player, STRUCTURE_NESTED, content,
                Component.text(visible.size() + " container(s) - by " + visible.get(0).operatorName(),
                        NamedTextColor.GOLD),
                () -> openRollbacks(player));
    }

    private void openItems(Player player, UUID rollbackId, SalvageSnapshot snap) {
        // Filter in-flight slots before rendering (defense-in-depth for a stale snap).
        UUID snapId = snap.id();
        List<StoredItem> items = snap.items().stream()
                .filter(item -> !inFlight.isInFlight(snapId, item.slot()))
                .toList();
        SalvageSnapshot filtered = snap.withItems(items);
        if (items.isEmpty()) {
            openChests(player, rollbackId); // nothing left here
            return;
        }
        List<Item> content = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            ItemStack stack = ItemSerialization.decode(items.get(i).data());
            if (stack != null && stack.getType() != Material.AIR) {
                content.add(new TakeItem(player, rollbackId, filtered, i, stack));
            }
        }
        openWindow(player, STRUCTURE_NESTED, content,
                Component.text("Salvage " + SalvageIcons.shortId(snap.id()), NamedTextColor.GOLD),
                () -> openChests(player, rollbackId));
    }

    // ---- window construction -------------------------------------------

    private void openWindow(Player player, String[] structure, List<Item> content, Component title) {
        openWindow(player, structure, content, title, null);
    }

    private void openWindow(Player player, String[] structure, List<Item> content, Component title,
                            Runnable back) {
        PagedGui.Builder<Item> builder = PagedGui.items()
                .setStructure(structure)
                .addIngredient('x', Markers.CONTENT_LIST_SLOT_HORIZONTAL)
                .addIngredient('#', filler())
                .addIngredient('<', new PrevItem())
                .addIngredient('>', new NextItem())
                .addIngredient('i', new InfoItem())
                .setContent(content);
        if (back != null) {
            builder.addIngredient('b', new BackItem(back));
        }
        Window.single()
                .setViewer(player)
                .setTitle(new AdventureComponentWrapper(title))
                .setGui(builder.build())
                .build()
                .open();
    }

    /**
     * Run a blocking store read on the store executor, then hand the result to
     * {@code open} on the main thread (InvUI window construction must run there).
     * A read failure is logged and drops the open rather than throwing off-thread.
     */
    private <T> void readThenOpen(Supplier<T> read, Consumer<T> open) {
        storeExecutor.execute(() -> {
            T result;
            try {
                result = read.get();
            } catch (RuntimeException ex) {
                logger.warning("Spyglass salvage GUI read failed: " + ex.getMessage());
                return;
            }
            mainExecutor.execute(() -> open.accept(result));
        });
    }

    private static ItemProvider filler() {
        return new ItemWrapper(SalvageIcons.button(Material.GRAY_STAINED_GLASS_PANE, " ", null));
    }

    // ---- items ---------------------------------------------------------

    /** Top level: click opens the rollback's containers. */
    private final class RollbackItem extends AbstractItem {
        private final Player player;
        private final SalvageStore.RollbackGroup group;

        RollbackItem(Player player, SalvageStore.RollbackGroup group) {
            this.player = player;
            this.group = group;
        }

        @Override
        public ItemProvider getItemProvider() {
            return new ItemWrapper(SalvageIcons.rollbackIcon(group));
        }

        @Override
        public void handleClick(ClickType clickType, Player who, InventoryClickEvent event) {
            openChests(player, group.rollbackId());
        }
    }

    /** Container level: click opens that container's items. */
    private final class ChestItem extends AbstractItem {
        private final Player player;
        private final UUID rollbackId;
        private final SalvageSnapshot snap;

        ChestItem(Player player, UUID rollbackId, SalvageSnapshot snap) {
            this.player = player;
            this.rollbackId = rollbackId;
            this.snap = snap;
        }

        @Override
        public ItemProvider getItemProvider() {
            return new ItemWrapper(SalvageIcons.chestIcon(snap));
        }

        @Override
        public void handleClick(ClickType clickType, Player who, InventoryClickEvent event) {
            openItems(player, rollbackId, snap);
        }
    }

    /** Item level: click takes the item into the player's inventory. */
    private final class TakeItem extends AbstractItem {
        private final Player player;
        private final UUID rollbackId;
        private final SalvageSnapshot snap;
        private final int index;
        private final ItemStack display;

        TakeItem(Player player, UUID rollbackId, SalvageSnapshot snap, int index, ItemStack display) {
            this.player = player;
            this.rollbackId = rollbackId;
            this.snap = snap;
            this.index = index;
            this.display = display;
        }

        @Override
        public ItemProvider getItemProvider() {
            return new ItemWrapper(display.clone());
        }

        @Override
        public void handleClick(ClickType clickType, Player who, InventoryClickEvent event) {
            SalvageWithdrawals.Outcome outcome = withdrawals.withdraw(player, snap, index);
            switch (outcome.status()) {
                case FULL -> player.sendMessage(
                        Component.text("Your inventory is full.", NamedTextColor.RED));
                case EMPTIED -> openChests(player, rollbackId);
                case TAKEN -> openItems(player, rollbackId, outcome.updated());
                case REFUSED, SKIPPED -> {
                    // Refused (dupe guard) or nothing to take: leave the view as-is.
                }
            }
        }
    }

    private final class BackItem extends AbstractItem {
        private final Runnable back;

        BackItem(Runnable back) {
            this.back = back;
        }

        @Override
        public ItemProvider getItemProvider() {
            return new ItemWrapper(SalvageIcons.button(Material.ARROW, "Back", NamedTextColor.YELLOW));
        }

        @Override
        public void handleClick(ClickType clickType, Player who, InventoryClickEvent event) {
            back.run();
        }
    }

    private static final class InfoItem extends AbstractItem {
        @Override
        public ItemProvider getItemProvider() {
            return new ItemWrapper(SalvageIcons.button(Material.PAPER, "Rollback Salvage",
                    NamedTextColor.GRAY));
        }

        @Override
        public void handleClick(ClickType clickType, Player who, InventoryClickEvent event) {
            // Info only.
        }
    }

    private static final class PrevItem extends PageItem {
        PrevItem() {
            super(false);
        }

        @Override
        public ItemProvider getItemProvider(PagedGui<?> gui) {
            Material material = gui.hasPreviousPage() ? Material.ARROW : Material.GRAY_STAINED_GLASS_PANE;
            String label = gui.hasPreviousPage() ? "Previous page" : " ";
            return new ItemWrapper(SalvageIcons.button(material, label, NamedTextColor.GREEN));
        }
    }

    private static final class NextItem extends PageItem {
        NextItem() {
            super(true);
        }

        @Override
        public ItemProvider getItemProvider(PagedGui<?> gui) {
            Material material = gui.hasNextPage() ? Material.ARROW : Material.GRAY_STAINED_GLASS_PANE;
            String label = gui.hasNextPage() ? "Next page" : " ";
            return new ItemWrapper(SalvageIcons.button(material, label, NamedTextColor.GREEN));
        }
    }
}
