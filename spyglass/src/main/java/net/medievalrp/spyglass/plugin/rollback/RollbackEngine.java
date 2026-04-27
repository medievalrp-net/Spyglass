package net.medievalrp.spyglass.plugin.rollback;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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

    public List<RollbackResult> applyAll(List<RollbackEffect> effects, CommandSender sender) {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("RollbackEngine.applyAll must run on the main thread.");
        }
        if (effects.isEmpty()) {
            return List.of();
        }

        RollbackResult[] resultArray = new RollbackResult[effects.size()];
        boolean useFawe = FaweRollback.isAvailable();

        // Phase 1 — cheap precondition check for every BlockReplace.
        // Just material + blockdata, no full BlockSnapshot capture (the
        // old code captured sign text / banner patterns / container
        // contents / jukebox record on every block just to throw them
        // away in {@link #matches} — wildly expensive per block).
        // Survivors land in the FAWE candidate list; failures land in
        // {@code resultArray} as Skipped right here.
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

        // Phase 2 — FAWE-batch every surviving BlockReplace (simple AND
        // complex). FAWE writes material+blockdata in chunk-batched bulk;
        // tile-entity contents (containers, signs, banners, jukeboxes)
        // get written by Phase 3 via Bukkit's BlockState.update — but
        // Phase 3 skips the redundant setType/setBlockData since FAWE
        // already did them.
        boolean[] faweApplied = useFawe
                ? FaweRollback.applyAll(faweCandidateEffects)
                : new boolean[faweCandidateEffects.size()];

        for (int i = 0; i < faweCandidateIndices.size(); i++) {
            int targetIndex = faweCandidateIndices.get(i);
            RollbackEffect.BlockReplace effect = faweCandidateEffects.get(i);
            BlockSnapshot replacement = effect.replacement();
            BlockLocation loc = effect.location();

            try {
                if (useFawe && faweApplied[i]) {
                    // Phase 3a — FAWE handled the bulk write. If this
                    // block carries tile-entity state, apply it now via
                    // Bukkit's BlockState.update; otherwise we're done.
                    if (!FaweRollback.isSimple(replacement)) {
                        Block block = BlockLocations.resolveWorld(loc).orElseThrow()
                                .getBlockAt(loc.x(), loc.y(), loc.z());
                        applyTileEntityState(block, replacement);
                    }
                } else {
                    // Phase 3b — FAWE either isn't available or rejected
                    // this entry; do the full per-block apply ourselves.
                    Block block = BlockLocations.resolveWorld(loc).orElseThrow()
                            .getBlockAt(loc.x(), loc.y(), loc.z());
                    applySnapshot(block, replacement);
                }
                RollbackEffect inverse = new RollbackEffect.BlockReplace(
                        loc, replacement, effect.expectedCurrent());
                resultArray[targetIndex] = new RollbackResult.Applied(effect, inverse);
                emitRollbackSourceRecord(sender, loc, replacement);
            } catch (RuntimeException thrown) {
                resultArray[targetIndex] = new RollbackResult.Skipped(effect,
                        new RollbackReason.Error("Unhandled error: " + thrown.getMessage()));
            }
        }

        // Phase 4 — ContainerSlotWrites grouped by location. The old
        // path did one Block.getState + cast + BlockState.update PER
        // SLOT (so a 30-item theft rollback was 30 getState/update
        // cycles for ONE chest); now it's one of each per chest.
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

        // Phase 5 — entity spawn/remove and any leftovers fall through
        // to the original per-effect dispatcher. These are rare in TNT
        // workloads (only Bukkit.getUnsafe().deserializeEntity drives
        // them) so per-block is fine.
        for (int index = 0; index < effects.size(); index++) {
            if (resultArray[index] != null) {
                continue;
            }
            if (sender instanceof Player player && index > 0 && index % 500 == 0) {
                player.sendActionBar(Component.text("Applying " + index + " / " + effects.size()));
            }
            RollbackEffect effect = effects.get(index);
            try {
                resultArray[index] = apply(effect);
            } catch (RuntimeException thrown) {
                resultArray[index] = new RollbackResult.Skipped(effect,
                        new RollbackReason.Error("Unhandled error: " + thrown.getMessage()));
            }
        }

        return List.of(resultArray);
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
                origin, source, location);
        boolean toAir = after == null || after.material() == Material.AIR;
        // Lowercase + hyphenated names match {@code shulker-open} et al
        // and dodge the case-sensitivity quirk in {@link
        // net.medievalrp.spyglass.api.event.EventCatalog#recordClassOf}
        // (it lowercases on lookup but the map preserves case).
        String event = toAir ? "rolled-break" : "rolled-place";
        String target = (after == null ? Material.AIR : after.material()).name();
        recorder.record(new BlockUseRecord(
                ctx.id(), event, ctx.occurred(), ctx.expiresAt(),
                ctx.origin(), ctx.source(), ctx.location(), target));
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
