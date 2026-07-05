package net.medievalrp.spyglass.plugin.command.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.medievalrp.spyglass.api.event.StoredItem;
import net.medievalrp.spyglass.plugin.command.render.Feedback;
import net.medievalrp.spyglass.plugin.salvage.SalvageSnapshot;
import net.medievalrp.spyglass.plugin.salvage.SalvageStore;
import net.medievalrp.spyglass.plugin.salvage.SalvageView;
import net.medievalrp.spyglass.plugin.salvage.SalvageWithdrawals;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

/**
 * Backs {@code /sg inventory}. On Minecraft versions with the InvUI GUI a player
 * gets the chest-icon browser; everywhere else (26.x and the console/RCON) it is
 * a text listing plus {@code /sg inventory <id>} to recover a container's items.
 * The command path has no inventory-click surface, so it is safe to serve on
 * versions whose GUI dupe-safety we cannot verify.
 */
public final class SalvageService {

    private final SalvageStore store;
    /** InvUI GUI on supported versions; {@code null} means command-only. */
    @Nullable
    private final SalvageView view;
    private final SalvageWithdrawals withdrawals;
    private final int listLimit;
    private final ServiceSupport support;

    public SalvageService(SalvageStore store, @Nullable SalvageView view, SalvageWithdrawals withdrawals,
                          int listLimit, ServiceSupport support) {
        this.store = store;
        this.view = view;
        this.withdrawals = withdrawals;
        this.listLimit = listLimit;
        this.support = support;
    }

    /** {@code /sg inventory} - open the GUI if available, else print the listing. */
    public void execute(CommandSender sender) {
        if (store == null || withdrawals == null) {
            sender.sendMessage(Feedback.error("Container salvage is not enabled on this backend."));
            return;
        }
        if (sender instanceof Player player && view != null) {
            // The GUI reads the store off-thread internally and opens on main.
            view.open(player);
            return;
        }
        // No GUI (26.x) or a non-player sender: text listing. store.list() is a
        // blocking DB query, so read off-thread and print back on the main thread.
        boolean recoverable = sender instanceof Player;
        support.onAsyncThread(() -> {
            List<SalvageSnapshot> snaps = store.list(listLimit);
            support.onMainThread(() -> renderListing(sender, snaps, recoverable));
        });
    }

    /** {@code /sg inventory <id>} - recover a container's items (players only). */
    public void withdraw(CommandSender sender, String rawId) {
        if (store == null || withdrawals == null) {
            sender.sendMessage(Feedback.error("Container salvage is not enabled on this backend."));
            return;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Feedback.error("Recover items in-game as a player."));
            return;
        }
        String id = rawId.strip();
        if (id.startsWith("#")) {
            id = id.substring(1);
        }
        String needle = id.toLowerCase(java.util.Locale.ROOT);
        if (needle.isEmpty()) {
            sender.sendMessage(Feedback.error("Usage: /sg inventory <id>"));
            return;
        }
        support.onAsyncThread(() -> {
            Resolution resolution = resolve(needle);
            support.onMainThread(() -> completeWithdraw(player, needle, resolution));
        });
    }

    private void completeWithdraw(Player player, String needle, Resolution resolution) {
        if (!player.isOnline()) {
            return;
        }
        switch (resolution.status()) {
            case NONE -> player.sendMessage(Feedback.error("No salvage snapshot matches '" + needle + "'."));
            case AMBIGUOUS -> player.sendMessage(Feedback.error(
                    "Ambiguous id '" + needle + "' - use more characters or the full id."));
            case FOUND -> {
                SalvageWithdrawals.BulkResult result = withdrawals.withdrawAll(player, resolution.snapshot());
                player.sendMessage(describe(result));
            }
        }
    }

    private Component describe(SalvageWithdrawals.BulkResult result) {
        if (result.itemsTaken() == 0) {
            return result.inventoryFull()
                    ? Feedback.warn("Your inventory is full - nothing recovered.")
                    : Feedback.info("Nothing left to recover in that container.");
        }
        String msg = "Recovered " + result.itemsTaken() + " item(s) in "
                + result.stacksTaken() + " stack(s).";
        if (!result.emptied()) {
            msg += result.inventoryFull()
                    ? " Inventory full - some items remain."
                    : " Some items remain.";
        }
        return Feedback.success(msg);
    }

    // ---- id resolution -------------------------------------------------

    private enum Status { NONE, AMBIGUOUS, FOUND }

    private record Resolution(Status status, @Nullable SalvageSnapshot snapshot) {
        static Resolution none() {
            return new Resolution(Status.NONE, null);
        }

        static Resolution ambiguous() {
            return new Resolution(Status.AMBIGUOUS, null);
        }

        static Resolution of(SalvageSnapshot snapshot) {
            return new Resolution(Status.FOUND, snapshot);
        }
    }

    /** Blocking; runs off the main thread. Accepts a full UUID or a unique prefix. */
    private Resolution resolve(String needle) {
        UUID exact = tryUuid(needle);
        if (exact != null) {
            Optional<SalvageSnapshot> snap = store.get(exact);
            return snap.map(Resolution::of).orElseGet(Resolution::none);
        }
        SalvageSnapshot match = null;
        for (SalvageSnapshot snap : store.list(listLimit)) {
            if (snap.id().toString().startsWith(needle)) {
                if (match != null) {
                    return Resolution.ambiguous();
                }
                match = snap;
            }
        }
        return match == null ? Resolution.none() : Resolution.of(match);
    }

    @Nullable
    private static UUID tryUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    // ---- text listing --------------------------------------------------

    private void renderListing(CommandSender sender, List<SalvageSnapshot> snaps, boolean recoverable) {
        if (snaps.isEmpty()) {
            sender.sendMessage(Feedback.bonus("No salvaged inventories."));
            return;
        }
        sender.sendMessage(Feedback.success("Salvaged inventories: " + snaps.size()));
        for (SalvageSnapshot snap : snaps) {
            String items = snap.items().stream()
                    .map(StoredItem::material)
                    .collect(Collectors.joining(", "));
            String shortId = snap.id().toString().substring(0, 8);
            Component line = Feedback.bonus("#" + shortId
                    + " " + snap.worldName() + " " + snap.x() + "," + snap.y() + "," + snap.z()
                    + " " + snap.containerType() + " [" + items + "] by " + snap.operatorName());
            if (recoverable) {
                line = line.append(recoverButton(snap.id()));
            }
            sender.sendMessage(line);
        }
        if (recoverable) {
            sender.sendMessage(Feedback.info("Click [Recover], or run /sg inventory <id>."));
        }
    }

    private static Component recoverButton(UUID id) {
        return Component.text(" [Recover]", NamedTextColor.GREEN)
                .clickEvent(ClickEvent.runCommand("/spyglass inventory " + id))
                .hoverEvent(HoverEvent.showText(
                        Component.text("Recover these items into your inventory", NamedTextColor.GRAY)));
    }
}
