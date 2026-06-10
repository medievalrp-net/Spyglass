package net.medievalrp.spyglass.plugin.command.service;

import net.medievalrp.spyglass.plugin.command.render.Feedback;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
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
        sender.sendMessage(Feedback.querying());
        int batchSize = config.limits().rollbackBatchSize();
        // The ledger read and the per-chunk fetches are store I/O over
        // sync HTTP / sockets, so the whole replay loop runs off the
        // main thread; only the chunked applies start on main.
        support.onAsyncThread(() -> {
            Optional<UndoStack.UndoReader> opened;
            try {
                opened = undoStack.openLatest(player.getUniqueId());
            } catch (RuntimeException ex) {
                support.onMainThread(() -> player.sendMessage(
                        Feedback.error("Undo lookup failed: " + ex.getMessage())));
                return;
            }
            if (opened.isEmpty()) {
                support.onMainThread(() -> player.sendMessage(
                        Feedback.error("You have no valid actions to undo")));
                return;
            }
            long startNanos = System.nanoTime();
            int applied = 0;
            int skipped = 0;
            int errors = 0;
            java.util.HashSet<String> chunks = new java.util.HashSet<>();
            // Replay one ledger chunk at a time (#17): the previous
            // whole-op materialization held every inverse effect in
            // heap at once and ran out of memory past ~250K effects.
            try (UndoStack.UndoReader reader = opened.get()) {
                int chunkNo = 0;
                Optional<List<RollbackEffect>> chunk;
                while ((chunk = reader.nextChunk()).isPresent()) {
                    chunkNo++;
                    List<RollbackEffect> effects = chunk.get();
                    if (effects.isEmpty()) {
                        continue;
                    }
                    // applyAllChunked must start on the main thread;
                    // block here so only one chunk is in flight (and
                    // in heap) at a time, like the rollback page loop.
                    CompletableFuture<List<RollbackResult>> fut = new CompletableFuture<>();
                    support.onMainThread(() ->
                            engine.applyAllChunked(effects, player, support, batchSize)
                                    .whenComplete((r, err) -> {
                                        if (err != null) {
                                            fut.completeExceptionally(err);
                                        } else {
                                            fut.complete(r);
                                        }
                                    }));
                    for (RollbackResult result : fut.join()) {
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
                    if (reader.chunkCount() > 1) {
                        int progressApplied = applied;
                        int progressChunk = chunkNo;
                        int progressTotal = reader.chunkCount();
                        support.onMainThread(() -> player.sendActionBar(
                                net.kyori.adventure.text.Component.text(
                                        "Undoing: " + progressApplied + " applied (chunk "
                                                + progressChunk + "/" + progressTotal + ")")));
                    }
                }
                // Only a fully-replayed operation leaves the ledger; a
                // failed replay stays poppable, and the force-overwrite
                // apply makes a retry over partially-undone ground safe.
                reader.tombstone();
            } catch (RuntimeException ex) {
                Throwable cause = ex instanceof CompletionException && ex.getCause() != null
                        ? ex.getCause() : ex;
                support.onMainThread(() -> player.sendMessage(
                        Feedback.error("Undo failed: " + cause.getMessage())));
                return;
            }
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
            int finalApplied = applied;
            int finalSkipped = skipped;
            int finalErrors = errors;
            int finalChunks = chunks.size();
            support.onMainThread(() -> player.sendMessage(
                    RollbackService.summaryLine(new RollbackService.Summary(
                            finalApplied, finalSkipped, finalErrors,
                            finalChunks, elapsedMs))));
        });
    }

    private static net.medievalrp.spyglass.api.util.BlockLocation locationOfEffect(
            net.medievalrp.spyglass.api.rollback.RollbackEffect effect) {
        return switch (effect) {
            case RollbackEffect.BlockReplace br -> br.location();
            case RollbackEffect.ContainerSlotWrite csw -> csw.location();
            case RollbackEffect.EntitySpawn es -> es.location();
            case RollbackEffect.EntityRemove er -> er.location();
            case RollbackEffect.Custom c -> c.location();
        };
    }
}
