package net.medievalrp.spyglass.plugin.command.service;

import net.medievalrp.spyglass.plugin.command.render.Feedback;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import net.medievalrp.spyglass.api.query.QueryPredicate;
import net.medievalrp.spyglass.api.query.QueryRequest;
import net.medievalrp.spyglass.api.rollback.RollbackEffect;
import net.medievalrp.spyglass.api.rollback.RollbackResult;
import net.medievalrp.spyglass.plugin.config.SpyglassConfig;
import net.medievalrp.spyglass.plugin.rollback.RollbackEngine;
import net.medievalrp.spyglass.plugin.rollback.UndoStack;
import net.medievalrp.spyglass.plugin.storage.UndoReferenceBson;
import net.medievalrp.spyglass.plugin.util.ChunkRelighter;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class UndoService {

    private final RollbackEngine engine;
    private final UndoStack undoStack;
    private final ServiceSupport support;
    private final SpyglassConfig config;
    private final RollbackService rollbackService;

    public UndoService(RollbackEngine engine, UndoStack undoStack,
                       ServiceSupport support, SpyglassConfig config,
                       RollbackService rollbackService) {
        this.engine = engine;
        this.undoStack = undoStack;
        this.support = support;
        this.config = config;
        this.rollbackService = rollbackService;
    }

    public void execute(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Feedback.error("You must be a player to use this command"));
            return;
        }
        sender.sendMessage(Feedback.querying());
        support.onAsyncThread(() -> {
            Optional<UndoStack.Popped> opened;
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
            switch (opened.get()) {
                case UndoStack.ReplayReference ref -> replayReference(player, ref);
                case UndoStack.LegacyOperation legacy -> replayLegacy(player, legacy);
            }
        });
    }

    // Reference operation: the records are the ledger. Rebuild the
    // stored request, bound it to records that existed when the
    // original operation ran, and stream it through the rollback
    // pipeline in the OPPOSITE direction. The reference is consumed
    // only on clean completion; the replayed job records its own
    // reference on the way out, so undo-of-undo works unchanged.
    private void replayReference(Player player, UndoStack.ReplayReference ref) {
        UndoReferenceBson.Reference decoded;
        try {
            decoded = UndoReferenceBson.decodeBase64(ref.referenceBase64());
        } catch (RuntimeException ex) {
            ref.close();
            support.onMainThread(() -> player.sendMessage(
                    Feedback.error("Undo reference unreadable: " + ex.getMessage())));
            return;
        }
        RollbackMode original;
        try {
            original = RollbackMode.valueOf(decoded.mode());
        } catch (IllegalArgumentException ex) {
            ref.close();
            support.onMainThread(() -> player.sendMessage(
                    Feedback.error("Undo reference has unknown mode " + decoded.mode())));
            return;
        }
        RollbackMode inverse = original == RollbackMode.ROLLBACK
                ? RollbackMode.RESTORE
                : RollbackMode.ROLLBACK;
        // Ceiling: replay only records that existed when the original
        // op was submitted — anything later (including the op's own
        // synthesized rolled-* records) is out of scope.
        List<QueryPredicate> bounded = new ArrayList<>(decoded.request().predicates());
        bounded.add(new QueryPredicate.Range("occurred", Instant.EPOCH, decoded.ceiling()));
        QueryRequest replay = new QueryRequest(bounded,
                decoded.request().sort(), decoded.request().limit(),
                decoded.request().flags(), decoded.request().grouping());
        String label = "undo " + ref.operationType().toLowerCase(Locale.ROOT)
                + " " + ref.operationId().toString().substring(0, 8);
        support.onMainThread(() -> rollbackService.executeReplay(
                player, replay, inverse, label,
                () -> support.onAsyncThread(() -> {
                    try {
                        ref.tombstone();
                    } catch (RuntimeException ex) {
                        // Next /undo pops it again; the replay is
                        // convergent, so a duplicate run is safe.
                    } finally {
                        ref.close();
                    }
                })));
    }

    // Pre-reference operation: stream the stored inverse effects chunk
    // by chunk through the chunked engine, one chunk in heap at a time.
    private void replayLegacy(Player player, UndoStack.LegacyOperation legacy) {
        int batchSize = config.limits().rollbackBatchSize();
        long startNanos = System.nanoTime();
        int applied = 0;
        int skipped = 0;
        int errors = 0;
        java.util.HashSet<String> chunks = new java.util.HashSet<>();
        // Touched chunks per world, packed for ChunkRelighter — the legacy
        // replay writes blocks through the same section-palette path that
        // skips the light engine, so it needs the same post-write relight.
        Map<UUID, Set<Long>> touchedByWorld = new HashMap<>();
        try {
            int chunkNo = 0;
            Optional<List<RollbackEffect>> chunk;
            while ((chunk = legacy.nextChunk()).isPresent()) {
                chunkNo++;
                List<RollbackEffect> effects = chunk.get();
                if (effects.isEmpty()) {
                    continue;
                }
                // applyAllChunked must start on the main thread; block
                // here so only one chunk is in flight at a time.
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
                            touchedByWorld.computeIfAbsent(loc.worldId(), k -> new java.util.HashSet<>())
                                    .add(ChunkRelighter.packChunk(loc.x() >> 4, loc.z() >> 4));
                        }
                    } else if (result instanceof RollbackResult.Skipped sk) {
                        skipped++;
                        if (sk.reason() instanceof
                                net.medievalrp.spyglass.api.rollback.RollbackReason.Error) {
                            errors++;
                        }
                    }
                }
                if (legacy.chunkCount() > 1) {
                    int progressApplied = applied;
                    int progressChunk = chunkNo;
                    int progressTotal = legacy.chunkCount();
                    support.onMainThread(() -> player.sendActionBar(
                            net.kyori.adventure.text.Component.text(
                                    "Undoing: " + progressApplied + " applied (chunk "
                                            + progressChunk + "/" + progressTotal + ")")));
                }
            }
            // Only a fully-replayed operation leaves the ledger; a
            // failed replay stays poppable, and the force-overwrite
            // apply makes a retry over partially-undone ground safe.
            legacy.tombstone();
        } catch (RuntimeException ex) {
            Throwable cause = ex instanceof CompletionException && ex.getCause() != null
                    ? ex.getCause() : ex;
            support.onMainThread(() -> player.sendMessage(
                    Feedback.error("Undo failed: " + cause.getMessage())));
            return;
        } finally {
            legacy.close();
        }
        // Restore lighting over the chunks this replay wrote (the engine's
        // direct section write skips the light engine). Same off-main
        // Starlight recompute the windowed path uses.
        if (applied > 0) {
            RollbackService.relightWrittenChunks(support, touchedByWorld);
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
