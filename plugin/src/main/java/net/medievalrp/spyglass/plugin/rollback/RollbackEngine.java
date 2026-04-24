package net.medievalrp.spyglass.plugin.rollback;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.medievalrp.spyglass.api.event.BlockSnapshot;
import net.medievalrp.spyglass.api.event.StoredItem;
import net.medievalrp.spyglass.api.rollback.RollbackEffect;
import net.medievalrp.spyglass.api.rollback.RollbackReason;
import net.medievalrp.spyglass.api.rollback.RollbackResult;
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

public final class RollbackEngine {

    public List<RollbackResult> applyAll(List<RollbackEffect> effects, CommandSender sender) {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("RollbackEngine.applyAll must run on the main thread.");
        }

        List<RollbackResult> results = new ArrayList<>(effects.size());
        for (int index = 0; index < effects.size(); index++) {
            if (sender instanceof Player player && index > 0 && index % 500 == 0) {
                player.sendActionBar(Component.text("Applying " + index + " / " + effects.size()));
            }
            results.add(apply(effects.get(index)));
        }
        return List.copyOf(results);
    }

    public RollbackResult apply(RollbackEffect effect) {
        return switch (effect) {
            case RollbackEffect.BlockReplace replace -> applyBlockReplace(replace);
            case RollbackEffect.ContainerSlotWrite slotWrite -> applyContainerSlotWrite(slotWrite);
            case RollbackEffect.EntitySpawn spawn -> applyEntitySpawn(spawn);
            case RollbackEffect.EntityRemove remove -> applyEntityRemove(remove);
        };
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

        ItemStack current = container.getInventory().getItem(effect.slot());
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
