package net.medievalrp.spyglass.plugin.rollback;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.medievalrp.spyglass.plugin.command.service.ServiceSupport;
import net.kyori.adventure.text.Component;
import net.medievalrp.spyglass.api.event.BlockPlaceRecord;
import net.medievalrp.spyglass.api.event.BlockSnapshot;
import net.medievalrp.spyglass.api.event.BlockUseRecord;
import net.medievalrp.spyglass.api.event.Origin;
import net.medievalrp.spyglass.api.event.RecordContext;
import net.medievalrp.spyglass.api.event.Source;
import net.medievalrp.spyglass.api.event.StoredItem;
import net.medievalrp.spyglass.api.rollback.RollbackEffect;
import net.medievalrp.spyglass.api.rollback.RollbackEffectHandler;
import net.medievalrp.spyglass.api.rollback.RollbackReason;
import net.medievalrp.spyglass.api.rollback.RollbackResult;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.plugin.listener.RecordingSupport;
import net.medievalrp.spyglass.plugin.pipeline.Recorder;
import net.medievalrp.spyglass.plugin.util.BlockLocations;
import net.medievalrp.spyglass.plugin.util.BlockSnapshots;
import net.medievalrp.spyglass.plugin.util.ItemSerialization;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Banner;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.Jukebox;
import org.bukkit.block.Sign;
import org.bukkit.block.banner.Pattern;
import org.bukkit.block.banner.PatternType;
import org.bukkit.block.sign.Side;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class RollbackEngine {

    private final Recorder recorder;
    private final RecordingSupport support;
    private java.util.function.Function<String, java.util.Optional<RollbackEffectHandler>> handlerLookup =
            type -> java.util.Optional.empty();

    /** Test-friendly default: no rollback-source records emitted. */
    public RollbackEngine() {
        this(null, null);
    }

    /**
     * Production constructor. When {@code recorder} and {@code support}
     * are non-null, every successfully-applied effect ALSO emits a
     * {@link BlockPlaceRecord} stamped with {@link Origin#rollback} and a
     * {@code "ROLLBACK"} environment source — so the wand on a rolled-
     * back block reads "ROLLBACK placed STONE" rather than nothing.
     */
    public RollbackEngine(Recorder recorder, RecordingSupport support) {
        this.recorder = recorder;
        this.support = support;
    }

    /**
     * Wire the lookup the engine uses to dispatch
     * {@link RollbackEffect.Custom} payloads to third-party handlers.
     * Set during plugin {@code onEnable}; tests leave the default
     * lookup (no handlers) in place.
     */
    public void setCustomEffectLookup(java.util.function.Function<String, java.util.Optional<RollbackEffectHandler>> lookup) {
        this.handlerLookup = lookup == null
                ? type -> java.util.Optional.empty()
                : lookup;
    }

    /**
     * Synchronous entry point — used by tests and by external callers that
     * already control their own scheduling. Wraps {@link #applyAllChunked}
     * with a {@link ServiceSupport#synchronous() synchronous} scheduler and
     * an unbounded batch so everything happens in a single call.
     *
     * <p>For production use, {@code RollbackService} calls
     * {@link #applyAllChunked} directly so the per-block apply phase can
     * yield between batches and stay under Paper's tick watchdog.
     */
    public List<RollbackResult> applyAll(List<RollbackEffect> effects, CommandSender sender) {
        return applyAllChunked(effects, sender, ServiceSupport.synchronous(), Integer.MAX_VALUE).join();
    }

    /**
     * Apply a rollback split across ticks.
     *
     * <p>Earlier revisions did the entire rollback in one main-thread call.
     * On the FAWE-less slow path each {@code BlockState.update} costs a few
     * ms; for a 2 000-block rollback that's tens of seconds in one tick,
     * which trips Paper's 60 s watchdog and crashes the server. Worse, the
     * synchronous flood of {@code rolled-place}/{@code rolled-break}
     * recorder events to ClickHouse OOM-killed CH at the same time, so the
     * data layer would tear down with the world half-rolled (the visible
     * "diagonal lines / 4×4 chunks" pattern operators reported).
     *
     * <p>This method now:
     * <ol>
     *   <li>Runs Phase 1 (precondition check) and Phase 2 (FAWE batch
     *       submit) inline — both are fast: Phase 1 is just block reads,
     *       Phase 2 hands the whole list to FAWE which schedules its own
     *       chunk-batched writes off-tick.</li>
     *   <li>Splits Phase 3 (per-block {@code BlockState.update}) into
     *       batches of {@code batchSize} effects per tick, yielding back
     *       to the main thread between batches via
     *       {@link ServiceSupport#onMainThreadLater}. Each batch also
     *       emits its own {@code rolled-*} recorder events, which keeps
     *       the ClickHouse insert rate proportional to the rollback
     *       throughput instead of dumping everything at once.</li>
     *   <li>Runs Phase 4 (container-slot batches) in one tick — small N
     *       (one batch per chest, not per slot) and already cheap.</li>
     *   <li>Splits Phase 5 (entity ops + custom handlers) the same way
     *       Phase 3 does.</li>
     * </ol>
     *
     * Returns a future that completes once every batch has run. The
     * caller (RollbackService) chains the summary line and undo-stack push
     * onto completion so the player gets feedback once everything's
     * actually applied — not when the work is just queued.
     *
     * <p>Must be invoked on the main thread (Phase 1 reads block state).
     * Subsequent batches are re-entered on the main thread by the
     * scheduler.
     *
     * @param effects   ordered list of rollback effects (already sorted
     *                  newest-first for ROLLBACK / oldest-first for RESTORE
     *                  by the caller)
     * @param sender    the operator running the command; receives action
     *                  bar progress messages once batching kicks in
     * @param scheduler bridges {@code Bukkit.getScheduler().runTaskLater}
     *                  for production and {@code Runnable.run()} for tests
     * @param batchSize number of effects to apply per tick before yielding;
     *                  {@link Integer#MAX_VALUE} = "do everything now"
     */
    public CompletableFuture<List<RollbackResult>> applyAllChunked(
            List<RollbackEffect> effects, CommandSender sender,
            ServiceSupport scheduler, int batchSize) {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("RollbackEngine.applyAllChunked must run on the main thread.");
        }
        CompletableFuture<List<RollbackResult>> done = new CompletableFuture<>();
        if (effects.isEmpty()) {
            done.complete(List.of());
            return done;
        }

        RollbackResult[] resultArray = new RollbackResult[effects.size()];
        boolean useFawe = FaweRollback.isAvailable();

        // Phase 1 — cheap precondition check for every BlockReplace.
        // Just material + blockdata, no full BlockSnapshot capture.
        List<Integer> faweCandidateIndices = new ArrayList<>();
        List<RollbackEffect.BlockReplace> faweCandidateEffects = new ArrayList<>();
        for (int index = 0; index < effects.size(); index++) {
            RollbackEffect effect = effects.get(index);
            if (!(effect instanceof RollbackEffect.BlockReplace br)) {
                continue;
            }
            Optional<World> world = BlockLocations.resolveWorld(br.location());
            if (world.isEmpty()) {
                resultArray[index] = new RollbackResult.Skipped(br,
                        new RollbackReason.InvalidLocation(br.location()));
                continue;
            }
            Block block = world.get().getBlockAt(br.location().x(), br.location().y(), br.location().z());
            BlockSnapshot expected = br.expectedCurrent();
            if (block.getType() != expected.material()
                    || !block.getBlockData().getAsString().equals(expected.blockData())) {
                BlockSnapshot lightweightActual = BlockSnapshots.of(
                        block.getType(), block.getBlockData().getAsString());
                resultArray[index] = new RollbackResult.Skipped(br,
                        new RollbackReason.BlockChanged(br.location(), expected, lightweightActual));
                continue;
            }
            faweCandidateIndices.add(index);
            faweCandidateEffects.add(br);
        }

        // Phase 2 — FAWE-batch submit. FAWE itself handles chunk-batching
        // off-tick; we just hand it the candidate list.
        boolean[] faweApplied = useFawe
                ? FaweRollback.applyAll(faweCandidateEffects)
                : new boolean[faweCandidateEffects.size()];

        // Phase 3 — kick off the chunked per-block apply loop.
        applyBlockReplaceBatch(0, effects, resultArray, faweCandidateIndices, faweCandidateEffects,
                faweApplied, useFawe, sender, scheduler, batchSize, done);
        return done;
    }

    /**
     * One tick's worth of Phase 3 work: apply up to {@code batchSize}
     * BlockReplace effects from {@code faweCandidate*} starting at index
     * {@code from}, then either schedule the next batch on the next tick
     * or hand off to Phase 4+5.
     */
    private void applyBlockReplaceBatch(int from,
                                        List<RollbackEffect> effects,
                                        RollbackResult[] resultArray,
                                        List<Integer> faweCandidateIndices,
                                        List<RollbackEffect.BlockReplace> faweCandidateEffects,
                                        boolean[] faweApplied,
                                        boolean useFawe,
                                        CommandSender sender,
                                        ServiceSupport scheduler,
                                        int batchSize,
                                        CompletableFuture<List<RollbackResult>> done) {
        int total = faweCandidateIndices.size();
        int to = Math.min(from + batchSize, total);
        // Track every chunk we touch this batch so we can force a full
        // chunk-data resend at the end. Without this, hundreds of single
        // block-change packets either get rate-limited by the client or
        // arrive out of order, producing the classic "pixelated grain"
        // look — the server has the right blocks, the client's render
        // cache is wrong. Pushing a fresh chunk packet wipes the stale
        // mesh in one shot. Map<worldId, Set<packed-chunk-coord>>.
        Map<UUID, Set<Long>> chunksTouched = new HashMap<>();
        for (int i = from; i < to; i++) {
            int targetIndex = faweCandidateIndices.get(i);
            RollbackEffect.BlockReplace effect = faweCandidateEffects.get(i);
            BlockSnapshot replacement = effect.replacement();
            BlockLocation loc = effect.location();
            try {
                if (useFawe && faweApplied[i]) {
                    if (!FaweRollback.isSimple(replacement)) {
                        Block block = BlockLocations.resolveWorld(loc).orElseThrow()
                                .getBlockAt(loc.x(), loc.y(), loc.z());
                        applyTileEntityState(block, replacement);
                    }
                } else {
                    Block block = BlockLocations.resolveWorld(loc).orElseThrow()
                            .getBlockAt(loc.x(), loc.y(), loc.z());
                    applySnapshot(block, replacement);
                }
                long packed = ((long) (loc.x() >> 4) << 32) | ((loc.z() >> 4) & 0xFFFFFFFFL);
                chunksTouched.computeIfAbsent(loc.worldId(), k -> new HashSet<>()).add(packed);
                RollbackEffect inverse = new RollbackEffect.BlockReplace(
                        loc, replacement, effect.expectedCurrent());
                resultArray[targetIndex] = new RollbackResult.Applied(effect, inverse);
                emitRollbackSourceRecord(sender, loc, replacement);
            } catch (RuntimeException thrown) {
                resultArray[targetIndex] = new RollbackResult.Skipped(effect,
                        new RollbackReason.Error("Unhandled error: " + thrown.getMessage()));
            }
        }
        // End-of-batch chunk refresh for every chunk we wrote to. Paper's
        // World.refreshChunk(x, z) sends the chunk-data packet to every
        // viewer, replacing whatever stale mesh the client had built up.
        refreshTouchedChunks(chunksTouched);
        if (sender instanceof Player p && total > batchSize && to < total) {
            p.sendActionBar(Component.text("Rolling back " + to + " / " + total));
        }
        if (to < total) {
            scheduler.onMainThreadLater(1L, () -> applyBlockReplaceBatch(
                    to, effects, resultArray, faweCandidateIndices, faweCandidateEffects,
                    faweApplied, useFawe, sender, scheduler, batchSize, done));
        } else {
            runContainerAndLeftover(effects, resultArray, sender, scheduler, batchSize, done);
        }
    }

    /**
     * Force a fresh chunk-data packet on every chunk we wrote to in the
     * batch. Cheap when called once per batch (~at most a few dozen
     * chunks even on a wide rollback); expensive if called per block.
     * Tolerates non-Bukkit test environments — if the world isn't
     * resolvable or {@code refreshChunk} throws, we just log and move on
     * (the worst case is the existing per-block-change packet path,
     * which is what the user already had).
     */
    private void refreshTouchedChunks(Map<UUID, Set<Long>> chunksByWorld) {
        for (Map.Entry<UUID, Set<Long>> entry : chunksByWorld.entrySet()) {
            World world;
            try {
                world = Bukkit.getWorld(entry.getKey());
            } catch (RuntimeException ex) {
                continue;
            }
            if (world == null) {
                continue;
            }
            for (long packed : entry.getValue()) {
                int cx = (int) (packed >> 32);
                int cz = (int) packed;
                try {
                    world.refreshChunk(cx, cz);
                } catch (RuntimeException ex) {
                    // Swallow — the client will eventually resync via
                    // its own chunk reload (re-render-distance, leave
                    // and rejoin the chunk, etc.). Don't fail the whole
                    // rollback over a single chunk that wouldn't refresh.
                }
            }
        }
    }

    /**
     * Phase 4 (containers, single-tick) → Phase 5 (entity ops + custom
     * handlers, chunked the same way Phase 3 is). Called once Phase 3 has
     * fully drained.
     */
    private void runContainerAndLeftover(List<RollbackEffect> effects,
                                         RollbackResult[] resultArray,
                                         CommandSender sender,
                                         ServiceSupport scheduler,
                                         int batchSize,
                                         CompletableFuture<List<RollbackResult>> done) {
        // Phase 4 — group ContainerSlotWrites by location and apply each
        // chest in a single getState/update cycle. Typically <100
        // locations even on big rollbacks; one tick is enough.
        Map<BlockLocation, List<Integer>> slotIndicesByLocation = new LinkedHashMap<>();
        Map<BlockLocation, List<RollbackEffect.ContainerSlotWrite>> slotEffectsByLocation = new LinkedHashMap<>();
        for (int index = 0; index < effects.size(); index++) {
            if (resultArray[index] != null) {
                continue;
            }
            if (effects.get(index) instanceof RollbackEffect.ContainerSlotWrite csw) {
                slotIndicesByLocation.computeIfAbsent(csw.location(), k -> new ArrayList<>()).add(index);
                slotEffectsByLocation.computeIfAbsent(csw.location(), k -> new ArrayList<>()).add(csw);
            }
        }
        for (BlockLocation location : slotIndicesByLocation.keySet()) {
            applyContainerBatch(location,
                    slotIndicesByLocation.get(location),
                    slotEffectsByLocation.get(location),
                    resultArray);
        }

        // Phase 5 — leftover effects (entity spawn/remove, custom).
        applyLeftoverBatch(0, effects, resultArray, sender, scheduler, batchSize, done);
    }

    /** Phase 5 batched the same way Phase 3 is. */
    private void applyLeftoverBatch(int from,
                                    List<RollbackEffect> effects,
                                    RollbackResult[] resultArray,
                                    CommandSender sender,
                                    ServiceSupport scheduler,
                                    int batchSize,
                                    CompletableFuture<List<RollbackResult>> done) {
        int total = effects.size();
        int processed = 0;
        int i = from;
        while (i < total && processed < batchSize) {
            if (resultArray[i] == null) {
                RollbackEffect effect = effects.get(i);
                try {
                    resultArray[i] = apply(effect);
                } catch (RuntimeException thrown) {
                    resultArray[i] = new RollbackResult.Skipped(effect,
                            new RollbackReason.Error("Unhandled error: " + thrown.getMessage()));
                }
                processed++;
            }
            i++;
        }
        if (i < total) {
            int next = i;
            scheduler.onMainThreadLater(1L, () -> applyLeftoverBatch(
                    next, effects, resultArray, sender, scheduler, batchSize, done));
        } else {
            done.complete(List.of(resultArray));
        }
    }

    /**
     * Apply ONLY the tile-entity portion of {@code snapshot} to
     * {@code block} — assumes the material + blockdata are already
     * correct (e.g. just written by FAWE). Calls {@code BlockState.update}
     * with physics off; skips the redundant {@code block.setType} and
     * {@code state.setBlockData} that {@link #applySnapshot} performs.
     */
    private void applyTileEntityState(Block block, BlockSnapshot snapshot) {
        BlockState state = block.getState();

        if (state instanceof Container container) {
            Inventory inventory = container.getSnapshotInventory();
            inventory.clear();
            for (StoredItem item : snapshot.containerItems()) {
                inventory.setItem(item.slot(), ItemSerialization.decode(item.data()));
            }
        }

        if (state instanceof Sign sign) {
            writeSign(sign, Side.FRONT, snapshot.signFront());
            writeSign(sign, Side.BACK, snapshot.signBack());
        }

        if (state instanceof Banner banner) {
            List<Pattern> patterns = snapshot.bannerPatterns().stream()
                    .map(this::parsePattern)
                    .flatMap(Optional::stream)
                    .toList();
            banner.setPatterns(patterns);
        }

        if (state instanceof Jukebox jukebox) {
            jukebox.getSnapshotInventory().setItem(0, ItemSerialization.decode(snapshot.jukeboxRecord()));
        }

        state.update(true, false);
    }

    /**
     * Apply every {@code ContainerSlotWrite} for one location in a single
     * {@code getState}/{@code update} cycle. Each slot still gets its
     * own precondition check + per-slot skip reason; only the surrounding
     * Bukkit roundtrips collapse.
     */
    private void applyContainerBatch(BlockLocation location,
                                     List<Integer> indices,
                                     List<RollbackEffect.ContainerSlotWrite> writes,
                                     RollbackResult[] resultArray) {
        Optional<World> world = BlockLocations.resolveWorld(location);
        if (world.isEmpty()) {
            for (int i = 0; i < indices.size(); i++) {
                resultArray[indices.get(i)] = new RollbackResult.Skipped(writes.get(i),
                        new RollbackReason.InvalidLocation(location));
            }
            return;
        }
        Block block = world.get().getBlockAt(location.x(), location.y(), location.z());
        BlockState state = block.getState();
        if (!(state instanceof Container container)) {
            for (int i = 0; i < indices.size(); i++) {
                resultArray[indices.get(i)] = new RollbackResult.Skipped(writes.get(i),
                        new RollbackReason.NotSupported("Target is no longer a container."));
            }
            return;
        }

        Inventory inventory = container.getSnapshotInventory();
        int size = inventory.getSize();
        boolean anyApplied = false;

        for (int i = 0; i < indices.size(); i++) {
            RollbackEffect.ContainerSlotWrite csw = writes.get(i);
            int slot = csw.slot();
            if (slot < 0 || slot >= size) {
                resultArray[indices.get(i)] = new RollbackResult.Skipped(csw,
                        new RollbackReason.NotSupported(
                                "Slot " + slot + " out of range for container (size " + size + ")."));
                continue;
            }
            ItemStack current = inventory.getItem(slot);
            if (!matches(csw.expectedCurrent(), slot, current)) {
                resultArray[indices.get(i)] = new RollbackResult.Skipped(csw,
                        new RollbackReason.Error("Container slot changed."));
                continue;
            }
            StoredItem replacement = csw.replacement();
            inventory.setItem(slot, replacement == null ? null : ItemSerialization.decode(replacement.data()));
            StoredItem inverseCurrent = ItemSerialization.storedItem(slot, current);
            RollbackEffect inverse = new RollbackEffect.ContainerSlotWrite(
                    location, slot, replacement, inverseCurrent);
            resultArray[indices.get(i)] = new RollbackResult.Applied(csw, inverse);
            anyApplied = true;
        }

        if (anyApplied) {
            state.update(true, false);
        }
    }

    /**
     * Emit a lightweight rollback-source record so the wand and search
     * show "ROLLBACK placed STONE" / "ROLLBACK broke STONE" at the
     * affected block. Uses {@link BlockUseRecord} (just context + target
     * material) rather than a {@link BlockPlaceRecord} that would carry
     * heavy {@link BlockSnapshot} before/after blobs — for a 150-block
     * rollback that's the difference between 150 lightweight records and
     * 150 records each holding two snapshots, which (combined with FAWE's
     * write buffer) was enough to push the JVM heap into FAWE's "slow
     * down to avoid OOM" mode and watchdog the tick.
     *
     * <p>Event name flips on the replacement: AIR target reads as
     * {@code "rolledBreak"} (undo of a place / restore-to-air), non-AIR
     * reads as {@code "rolledPlace"} (rollback of a break / restore from
     * air). Past-tense rendering is configured in {@code config.conf}
     * under {@code events.rolledBreak} / {@code events.rolledPlace}.
     */
    private void emitRollbackSourceRecord(CommandSender sender, BlockLocation location, BlockSnapshot after) {
        if (recorder == null || support == null) {
            return;
        }
        String operatorName = sender == null ? "console" : sender.getName();
        Instant occurred = support.now();
        Source source = Source.environment("ROLLBACK");
        Origin origin = Origin.rollback(operatorName);
        // Build RecordContext directly with a fast-random UUID rather
        // than going through {@code support.context} which delegates to
        // {@link RecordContext#fresh} (uses SecureRandom). 150 rollback
        // blocks = 150 entropy reads on the main thread otherwise.
        RecordContext ctx = new RecordContext(
                RecordingSupport.fastRandomUUID(),
                occurred,
                support.expiresAt(occurred),
                origin, source, location, support.serverName());
        boolean toAir = after == null || after.material() == Material.AIR;
        // Lowercase + hyphenated names match {@code shulker-open} et al
        // and dodge the case-sensitivity quirk in {@link
        // net.medievalrp.spyglass.api.event.EventCatalog#recordClassOf}
        // (it lowercases on lookup but the map preserves case).
        String event = toAir ? "rolled-break" : "rolled-place";
        String target = (after == null ? Material.AIR : after.material()).name();
        recorder.record(new BlockUseRecord(
                ctx.id(), event, ctx.occurred(), ctx.expiresAt(),
                ctx.origin(), ctx.source(), ctx.location(), ctx.server(), target));
    }

    public RollbackResult apply(RollbackEffect effect) {
        return switch (effect) {
            case RollbackEffect.BlockReplace replace -> applyBlockReplace(replace);
            case RollbackEffect.ContainerSlotWrite slotWrite -> applyContainerSlotWrite(slotWrite);
            case RollbackEffect.EntitySpawn spawn -> applyEntitySpawn(spawn);
            case RollbackEffect.EntityRemove remove -> applyEntityRemove(remove);
            case RollbackEffect.Custom custom -> applyCustom(custom);
        };
    }

    private RollbackResult applyCustom(RollbackEffect.Custom effect) {
        java.util.Optional<RollbackEffectHandler> handler = handlerLookup.apply(effect.type());
        if (handler.isEmpty()) {
            return new RollbackResult.Skipped(effect, new RollbackReason.NotSupported(
                    "No handler registered for custom effect type '" + effect.type() + "'."));
        }
        try {
            RollbackResult result = handler.get().apply(effect);
            return result == null
                    ? new RollbackResult.Skipped(effect,
                            new RollbackReason.Error("Custom handler returned null."))
                    : result;
        } catch (RuntimeException thrown) {
            return new RollbackResult.Skipped(effect, new RollbackReason.Error(
                    "Custom handler '" + effect.type() + "' threw: " + thrown.getMessage()));
        }
    }

    private RollbackResult applyEntitySpawn(RollbackEffect.EntitySpawn effect) {
        if (effect.serializedEntity() == null || effect.serializedEntity().isBlank()) {
            return new RollbackResult.Skipped(effect, new RollbackReason.NotSupported(
                    "Entity NBT not captured (record is pre-Block-11)."));
        }
        Optional<World> world = BlockLocations.resolveWorld(effect.location());
        if (world.isEmpty()) {
            return new RollbackResult.Skipped(effect, new RollbackReason.InvalidLocation(effect.location()));
        }
        Location location = new Location(world.get(),
                effect.location().x() + 0.5, effect.location().y(), effect.location().z() + 0.5);
        try {
            byte[] bytes = Base64.getDecoder().decode(effect.serializedEntity());
            Entity entity = Bukkit.getUnsafe().deserializeEntity(bytes, world.get(), true, false);
            if (entity == null) {
                return new RollbackResult.Skipped(effect, new RollbackReason.NotSupported(
                        "Entity deserialization returned null."));
            }
            entity.teleport(location);
            RollbackEffect inverse = new RollbackEffect.EntityRemove(
                    effect.location(), effect.entityType(), entity.getUniqueId().toString());
            return new RollbackResult.Applied(effect, inverse);
        } catch (Throwable thrown) {
            return new RollbackResult.Skipped(effect, new RollbackReason.Error(
                    "Entity spawn failed: " + thrown.getMessage()));
        }
    }

    private RollbackResult applyEntityRemove(RollbackEffect.EntityRemove effect) {
        if (effect.entityId() == null || effect.entityId().isBlank()) {
            return new RollbackResult.Skipped(effect, new RollbackReason.NotSupported("No entity id."));
        }
        UUID entityId;
        try {
            entityId = UUID.fromString(effect.entityId());
        } catch (IllegalArgumentException ex) {
            return new RollbackResult.Skipped(effect, new RollbackReason.NotSupported("Invalid entity id."));
        }
        Entity entity = Bukkit.getEntity(entityId);
        if (entity == null) {
            return new RollbackResult.Skipped(effect, new RollbackReason.NotSupported("Entity not found."));
        }
        entity.remove();
        RollbackEffect inverse = new RollbackEffect.EntitySpawn(effect.location(), effect.entityType(), null);
        return new RollbackResult.Applied(effect, inverse);
    }

    private RollbackResult applyBlockReplace(RollbackEffect.BlockReplace effect) {
        Optional<World> world = BlockLocations.resolveWorld(effect.location());
        if (world.isEmpty()) {
            return new RollbackResult.Skipped(effect, new RollbackReason.InvalidLocation(effect.location()));
        }

        Block block = world.get().getBlockAt(effect.location().x(), effect.location().y(), effect.location().z());
        BlockSnapshot actual = BlockSnapshots.capture(block.getState());
        if (!matches(effect.expectedCurrent(), actual)) {
            return new RollbackResult.Skipped(effect, new RollbackReason.BlockChanged(effect.location(), effect.expectedCurrent(), actual));
        }

        applySnapshot(block, effect.replacement());
        RollbackEffect inverse = new RollbackEffect.BlockReplace(effect.location(), effect.replacement(), actual);
        return new RollbackResult.Applied(effect, inverse);
    }

    private RollbackResult applyContainerSlotWrite(RollbackEffect.ContainerSlotWrite effect) {
        Optional<World> world = BlockLocations.resolveWorld(effect.location());
        if (world.isEmpty()) {
            return new RollbackResult.Skipped(effect, new RollbackReason.InvalidLocation(effect.location()));
        }
        Block block = world.get().getBlockAt(effect.location().x(), effect.location().y(), effect.location().z());
        if (!(block.getState() instanceof Container container)) {
            return new RollbackResult.Skipped(effect, new RollbackReason.NotSupported("Target is no longer a container."));
        }

        // Some Bukkit inventory events report slot = -1 when the
        // change wasn't tied to a specific slot (auto-stacking, drag
        // drops, etc.) and the original container is sometimes wider
        // than the current one (player downgraded chest -> hopper).
        // Validate the slot against the live inventory size before
        // calling getItem(), which throws IndexOutOfBoundsException
        // for out-of-range indices.
        int slot = effect.slot();
        int size = container.getInventory().getSize();
        if (slot < 0 || slot >= size) {
            return new RollbackResult.Skipped(effect,
                    new RollbackReason.NotSupported(
                            "Slot " + slot + " out of range for container (size " + size + ")."));
        }

        ItemStack current = container.getInventory().getItem(slot);
        if (!matches(effect.expectedCurrent(), effect.slot(), current)) {
            return new RollbackResult.Skipped(effect, new RollbackReason.Error("Container slot changed."));
        }

        StoredItem replacement = effect.replacement();
        container.getInventory().setItem(effect.slot(), replacement == null ? null : ItemSerialization.decode(replacement.data()));
        StoredItem inverseCurrent = ItemSerialization.storedItem(effect.slot(), current);
        RollbackEffect inverse = new RollbackEffect.ContainerSlotWrite(effect.location(), effect.slot(), replacement, inverseCurrent);
        return new RollbackResult.Applied(effect, inverse);
    }

    private void applySnapshot(Block block, BlockSnapshot snapshot) {
        block.setType(snapshot.material(), false);
        BlockState state = block.getState();
        state.setBlockData(Bukkit.createBlockData(snapshot.blockData()));

        if (state instanceof Container container) {
            Inventory inventory = container.getSnapshotInventory();
            inventory.clear();
            for (StoredItem item : snapshot.containerItems()) {
                inventory.setItem(item.slot(), ItemSerialization.decode(item.data()));
            }
        }

        if (state instanceof Sign sign) {
            writeSign(sign, Side.FRONT, snapshot.signFront());
            writeSign(sign, Side.BACK, snapshot.signBack());
        }

        if (state instanceof Banner banner) {
            List<Pattern> patterns = snapshot.bannerPatterns().stream()
                    .map(this::parsePattern)
                    .flatMap(Optional::stream)
                    .toList();
            banner.setPatterns(patterns);
        }

        if (state instanceof Jukebox jukebox) {
            jukebox.getSnapshotInventory().setItem(0, ItemSerialization.decode(snapshot.jukeboxRecord()));
        }

        state.update(true, false);
    }

    private boolean matches(BlockSnapshot expected, BlockSnapshot actual) {
        return expected.material() == actual.material() && expected.blockData().equals(actual.blockData());
    }

    private boolean matches(StoredItem expected, int slot, ItemStack current) {
        if (expected == null) {
            return current == null || current.getType() == Material.AIR;
        }
        return expected.slot() == slot
                && current != null
                && expected.material().equals(current.getType().name())
                && expected.data().equals(ItemSerialization.encode(current));
    }

    private void writeSign(Sign sign, Side side, List<String> lines) {
        for (int index = 0; index < Math.min(4, lines.size()); index++) {
            sign.getSide(side).line(index, Component.text(lines.get(index)));
        }
    }

    private Optional<Pattern> parsePattern(String value) {
        String[] parts = value.split(":", 2);
        if (parts.length != 2) {
            return Optional.empty();
        }
        try {
            PatternType type = PatternType.getByIdentifier(parts[1]);
            if (type == null) {
                return Optional.empty();
            }
            return Optional.of(new Pattern(org.bukkit.DyeColor.valueOf(parts[0]), type));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }
}
