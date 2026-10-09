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
import net.medievalrp.spyglass.api.capture.ItemSerialization;
import net.medievalrp.spyglass.plugin.gui.Cell;
import net.medievalrp.spyglass.plugin.gui.Gui;
import net.medievalrp.spyglass.plugin.gui.PagedMenu;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * Rollback salvage browser: three extract-only levels
 * (rollbacks -> containers -> items) with pagination and click safety.
 *
 * <p>The extract path goes through the shared {@link SalvageWithdrawals}
 * (dupe-guarded), re-reads filter in-flight slots
 * ({@link InFlightTracker}), store reads run off the main thread and the menu
 * opens back on it, and every take is logged. {@link Gui} menus cancel every
 * interaction, so the GUI is inherently extract-only (no inserts,
 * shift-clicks, number-key swaps, drags, or hopper pulls move items).
 *
 * <p>Menus switch only on the main thread and never inside the click that
 * caused the switch: a level that needs no store read is deferred to the next
 * tick, one that does reaches the main thread through {@code mainExecutor}.
 */
final class SalvageGuiView implements SalvageView {

    private static final int FETCH_CAP = 2000;

    private final Gui gui;
    private final SalvageStore store;
    private final Executor storeExecutor;
    private final Executor mainExecutor;
    private final int rollbackListLimit;
    private final Logger logger;
    private final SalvageWithdrawals withdrawals;
    private final InFlightTracker inFlight;

    SalvageGuiView(Gui gui, SalvageStore store, Executor storeExecutor, Executor mainExecutor,
                   SalvageWithdrawals withdrawals, int rollbackListLimit, Logger logger) {
        this.gui = gui;
        this.store = store;
        this.storeExecutor = storeExecutor;
        this.mainExecutor = mainExecutor;
        this.rollbackListLimit = rollbackListLimit;
        this.logger = logger;
        this.withdrawals = withdrawals;
        // GUI views of the same snapshot share in-flight slots.
        this.inFlight = withdrawals.inFlight();
    }

    @Override
    public void open(Player player) {
        openRollbacks(player, false);
    }

    // ---- level openers -------------------------------------------------

    private void openRollbacks(Player player, boolean fromMenu) {
        readThenOpen(player, fromMenu, () -> store.listRollbacks(rollbackListLimit),
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
        List<Cell> content = new ArrayList<>(groups.size());
        for (SalvageStore.RollbackGroup group : groups) {
            // Top level: click opens the rollback's containers.
            content.add(new Cell(SalvageIcons.rollbackIcon(group),
                    who -> openChests(player, group.rollbackId())));
        }
        gui.open(player, page(Component.text("Rollback Salvage"), content, null));
    }

    private void openChests(Player player, UUID rollbackId) {
        readThenOpen(player, true, () -> store.listByRollback(rollbackId, FETCH_CAP),
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
            openRollbacks(player, true); // this rollback was fully recovered
            return;
        }
        List<Cell> content = new ArrayList<>(visible.size());
        for (SalvageSnapshot snap : visible) {
            // Container level: click opens that container's items.
            content.add(new Cell(SalvageIcons.chestIcon(snap),
                    who -> gui.nextTick(() -> openItems(player, rollbackId, snap))));
        }
        gui.open(player, page(
                Component.text(visible.size() + " container(s) - by " + visible.get(0).operatorName()),
                content, () -> openRollbacks(player, true)));
    }

    private void openItems(Player player, UUID rollbackId, SalvageSnapshot snap) {
        if (!player.isOnline()) {
            return;
        }
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
        List<Cell> content = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            ItemStack stack = ItemSerialization.decode(items.get(i).data());
            if (stack != null && stack.getType() != Material.AIR) {
                int index = i;
                // Item level: click takes the item into the player's inventory.
                content.add(new Cell(stack, who -> take(player, rollbackId, filtered, index)));
            }
        }
        gui.open(player, page(Component.text("Salvage " + SalvageIcons.shortId(snap.id())),
                content, () -> openChests(player, rollbackId)));
    }

    /** Runs inside the click; the withdraw is synchronous, any menu change is deferred. */
    private void take(Player player, UUID rollbackId, SalvageSnapshot snap, int index) {
        SalvageWithdrawals.Outcome outcome = withdrawals.withdraw(player, snap, index);
        switch (outcome.status()) {
            case FULL -> player.sendMessage(
                    Component.text("Your inventory is full.", NamedTextColor.RED));
            case EMPTIED -> openChests(player, rollbackId);
            case TAKEN -> gui.nextTick(() -> openItems(player, rollbackId, outcome.updated()));
            case REFUSED, SKIPPED -> {
                // Refused (dupe guard) or nothing to take: leave the view as-is.
            }
        }
    }

    // ---- menu construction ---------------------------------------------

    private static PagedMenu page(Component title, List<Cell> content, @Nullable Runnable back) {
        ItemStack filler = SalvageIcons.button(Material.GRAY_STAINED_GLASS_PANE, " ", null);
        return new PagedMenu(title, content,
                Cell.icon(SalvageIcons.button(Material.PAPER, "Rollback Salvage", NamedTextColor.GRAY)),
                back == null ? null : new Cell(
                        SalvageIcons.button(Material.ARROW, "Back", NamedTextColor.YELLOW), who -> back.run()),
                filler,
                SalvageIcons.button(Material.ARROW, "Previous page", NamedTextColor.GREEN),
                SalvageIcons.button(Material.ARROW, "Next page", NamedTextColor.GREEN));
    }

    /**
     * Run a blocking store read on the store executor, then hand the result to
     * {@code open} on the main thread. A read failure is logged and drops the
     * open rather than throwing off-thread. A level reached from inside the
     * GUI is dropped if the player closed the menu while the read ran.
     */
    private <T> void readThenOpen(Player player, boolean fromMenu, Supplier<T> read, Consumer<T> open) {
        storeExecutor.execute(() -> {
            T result;
            try {
                result = read.get();
            } catch (RuntimeException ex) {
                logger.warning("Spyglass salvage GUI read failed: " + ex.getMessage());
                return;
            }
            mainExecutor.execute(() -> {
                if (fromMenu && !gui.isViewing(player)) {
                    return;
                }
                open.accept(result);
            });
        });
    }
}
