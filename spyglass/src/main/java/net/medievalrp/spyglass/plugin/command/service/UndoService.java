package net.medievalrp.spyglass.plugin.command.service;

import net.medievalrp.spyglass.plugin.command.render.Feedback;

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
            sender.sendMessage(Feedback.error("You must be a player to use this command"));
            return;
        }
        // Pop runs a ClickHouse query + tombstone insert; both are
        // synchronous HTTP. Bouncing off the main thread keeps the tick
        // alive — same pattern as {@link RollbackService}'s push.
        sender.sendMessage(Feedback.querying());
        support.onAsyncThread(() -> {
            Optional<UndoStack.UndoOperation> popped;
            try {
                popped = undoStack.pop(player.getUniqueId());
            } catch (RuntimeException ex) {
                support.onMainThread(() -> player.sendMessage(
                        Feedback.error("Undo lookup failed: " + ex.getMessage())));
                return;
            }
            if (popped.isEmpty()) {
                support.onMainThread(() -> player.sendMessage(
                        Feedback.error("You have no valid actions to undo")));
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
                player.sendMessage(RollbackService.summaryLine(
                        new RollbackService.Summary(applied, skipped, List.of())));
            });
        });
    }
}
