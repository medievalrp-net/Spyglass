package net.medievalrp.spyglass.plugin.command.service;

import net.medievalrp.spyglass.plugin.command.render.Feedback;

import java.util.List;
import java.util.Optional;
import net.medievalrp.spyglass.api.rollback.RollbackEffect;
import net.medievalrp.spyglass.api.rollback.RollbackResult;
import net.medievalrp.spyglass.plugin.config.SpyglassConfig;
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
    private final SpyglassConfig config;

    public UndoService(RollbackEngine engine, UndoStack undoStack,
                       ServiceSupport support, SpyglassConfig config) {
        this.engine = engine;
        this.undoStack = undoStack;
        this.support = support;
        this.config = config;
    }

    public void execute(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Feedback.error("You must be a player to use this command"));
            return;
        }
        // pop() does a CH query plus tombstone insert over sync HTTP,
        // so run it off the main thread to keep ticks alive.
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
            int batchSize = config.limits().rollbackBatchSize();
            // Run the apply through the chunked engine so a large
            // undo yields between chunks like a regular rollback.
            // applyAllChunked must start on the main thread.
            support.onMainThread(() -> {
                long startNanos = System.nanoTime();
                engine.applyAllChunked(effects, player, support, batchSize)
                        .thenAccept(results -> {
                            int applied = 0;
                            int skipped = 0;
                            int errors = 0;
                            java.util.HashSet<String> chunks = new java.util.HashSet<>();
                            for (RollbackResult result : results) {
                                if (result instanceof RollbackResult.Applied app) {
                                    applied++;
                                    var loc = locationOfEffect(app.effect());
                                    if (loc != null) {
                                        chunks.add(loc.worldId() + ":"
                                                + (loc.x() >> 4) + ":" + (loc.z() >> 4));
                                    }
                                } else if (result instanceof RollbackResult.Skipped sk) {
                                    skipped++;
                                    if (sk.reason() instanceof
                                            net.medievalrp.spyglass.api.rollback.RollbackReason.Error) {
                                        errors++;
                                    }
                                }
                            }
                            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
                            int finalApplied = applied;
                            int finalSkipped = skipped;
                            int finalErrors = errors;
                            int finalChunks = chunks.size();
                            support.onMainThread(() -> player.sendMessage(
                                    RollbackService.summaryLine(
                                            new RollbackService.Summary(
                                                    finalApplied, finalSkipped, finalErrors,
                                                    finalChunks, elapsedMs, List.of()))));
                        })
                        .exceptionally(throwable -> {
                            support.onMainThread(() -> player.sendMessage(
                                    Feedback.error("Undo failed: " + throwable.getMessage())));
                            return null;
                        });
            });
        });
    }

    private static net.medievalrp.spyglass.api.util.BlockLocation locationOfEffect(
            net.medievalrp.spyglass.api.rollback.RollbackEffect effect) {
        return switch (effect) {
            case net.medievalrp.spyglass.api.rollback.RollbackEffect.BlockReplace br -> br.location();
            case net.medievalrp.spyglass.api.rollback.RollbackEffect.ContainerSlotWrite csw -> csw.location();
            case net.medievalrp.spyglass.api.rollback.RollbackEffect.EntitySpawn es -> es.location();
            case net.medievalrp.spyglass.api.rollback.RollbackEffect.EntityRemove er -> er.location();
            case net.medievalrp.spyglass.api.rollback.RollbackEffect.Custom c -> c.location();
        };
    }
}
