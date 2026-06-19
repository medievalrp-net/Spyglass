package net.medievalrp.spyglass.plugin.command.service;

import java.util.List;
import java.util.stream.Collectors;
import net.medievalrp.spyglass.api.event.StoredItem;
import net.medievalrp.spyglass.plugin.command.render.Feedback;
import net.medievalrp.spyglass.plugin.salvage.SalvageGui;
import net.medievalrp.spyglass.plugin.salvage.SalvageSnapshot;
import net.medievalrp.spyglass.plugin.salvage.SalvageStore;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Backs {@code /sg inventory}. A player gets the chest-icon GUI; the console /
 * RCON (no GUI possible) gets a text listing — which also makes the feature
 * scriptable for the regression harness.
 */
public final class SalvageService {

    private final SalvageStore store;
    private final SalvageGui gui;
    private final int listLimit;

    public SalvageService(SalvageStore store, SalvageGui gui, int listLimit) {
        this.store = store;
        this.gui = gui;
        this.listLimit = listLimit;
    }

    public void execute(CommandSender sender) {
        if (store == null || gui == null) {
            sender.sendMessage(Feedback.error("Container salvage is not enabled on this backend."));
            return;
        }
        if (sender instanceof Player player) {
            gui.openRollbacks(player);
            return;
        }
        List<SalvageSnapshot> snaps = store.list(listLimit);
        if (snaps.isEmpty()) {
            sender.sendMessage(Feedback.bonus("No salvaged inventories."));
            return;
        }
        sender.sendMessage(Feedback.success("Salvaged inventories: " + snaps.size()));
        for (SalvageSnapshot snap : snaps) {
            String items = snap.items().stream()
                    .map(StoredItem::material)
                    .collect(Collectors.joining(", "));
            sender.sendMessage(Feedback.bonus("#" + snap.id().toString().substring(0, 8)
                    + " " + snap.worldName() + " " + snap.x() + "," + snap.y() + "," + snap.z()
                    + " " + snap.containerType() + " [" + items + "] by " + snap.operatorName()));
        }
    }
}
