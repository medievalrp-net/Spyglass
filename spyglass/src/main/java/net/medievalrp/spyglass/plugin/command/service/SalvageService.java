package net.medievalrp.spyglass.plugin.command.service;

import java.util.logging.Level;
import java.util.logging.Logger;
import net.medievalrp.spyglass.plugin.command.render.Feedback;
import net.medievalrp.spyglass.plugin.salvage.SalvageStore;
import net.medievalrp.spyglass.plugin.salvage.SalvageView;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

/** Opens the inventory-only rollback salvage browser. */
public final class SalvageService {
    private final SalvageStore store;
    @Nullable private final SalvageView view;
    private final Logger logger;

    public SalvageService(SalvageStore store, @Nullable SalvageView view, Logger logger) {
        this.store = store;
        this.view = view;
        this.logger = logger;
    }

    public void execute(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Feedback.error("Open rollback salvage in-game as a player."));
            return;
        }
        if (store == null) {
            sender.sendMessage(Feedback.error("Container salvage is not enabled on this backend."));
            return;
        }
        if (view == null) {
            sender.sendMessage(Feedback.error("The rollback salvage GUI is unavailable. Contact an administrator."));
            return;
        }
        try {
            view.open(player);
        } catch (RuntimeException ex) {
            logger.log(Level.WARNING, "Spyglass salvage GUI failed", ex);
            sender.sendMessage(Feedback.error("Could not open the rollback salvage GUI. Contact an administrator."));
        }
    }
}
