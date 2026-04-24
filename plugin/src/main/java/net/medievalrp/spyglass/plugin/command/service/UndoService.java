package net.medievalrp.spyglass.plugin.command.service;

import java.util.List;
import java.util.Optional;
import net.medievalrp.spyglass.api.rollback.RollbackEffect;
import net.medievalrp.spyglass.api.rollback.RollbackResult;
import net.medievalrp.spyglass.plugin.rollback.RollbackEngine;
import net.medievalrp.spyglass.plugin.rollback.UndoStack;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class UndoService {

    private final RollbackEngine engine;
    private final UndoStack undoStack;
    private final ServiceSupport support;

    public UndoService(RollbackEngine engine, UndoStack undoStack, ServiceSupport support) {
        this.engine = engine;
        this.undoStack = undoStack;
        this.support = support;
    }

    public void execute(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ServiceSupport.errorMessage("Only players can undo."));
            return;
        }
        Optional<UndoStack.UndoOperation> popped = undoStack.pop(player.getUniqueId());
        if (popped.isEmpty()) {
            player.sendMessage(ServiceSupport.warnMessage("Nothing to undo."));
            return;
        }
        List<RollbackEffect> effects = popped.get().inverseEffects();
        support.onMainThread(() -> {
            List<RollbackResult> results = engine.applyAll(effects, player);
            int applied = 0;
            int skipped = 0;
            for (RollbackResult result : results) {
                if (result instanceof RollbackResult.Applied) {
                    applied++;
                } else {
                    skipped++;
                }
            }
            player.sendMessage(RollbackService.summaryLine(new RollbackService.Summary(applied, skipped, List.of())));
        });
    }
}
