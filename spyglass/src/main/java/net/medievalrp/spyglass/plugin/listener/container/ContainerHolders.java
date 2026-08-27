package net.medievalrp.spyglass.plugin.listener.container;

import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.plugin.util.BlockLocations;
import org.bukkit.Location;
import org.bukkit.block.Container;
import org.bukkit.entity.AbstractHorse;
import org.bukkit.entity.ChestBoat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.minecart.HopperMinecart;
import org.bukkit.entity.minecart.StorageMinecart;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Resolve any inventory holder the container listeners record into a uniform
 * (location, type-name) pair, shared by the click, drag, and open/close
 * listeners so the set of trackable containers cannot drift between them
 * (#347 was exactly that drift: clicks knew about minecarts, but nothing knew
 * about entity-held inventories).
 *
 * <p>Covered: block containers, storage/hopper minecarts, the horse family
 * ({@link AbstractHorse}: horse, donkey, mule, llama, camel - saddle, armor,
 * and chest slots are all ordinary slots of the same entity-held inventory),
 * and chest boats. Entity-held inventories record against the entity's
 * current location - they move, so the recorded location is a snapshot at
 * event time, the storage-minecart precedent. Returns null for holders we
 * don't track (player inventories, anvils, brewing stands, ...).
 */
@ApiStatus.Internal
final class ContainerHolders {

    /** A trackable container: where it is and what to call it. */
    record Target(BlockLocation location, String type) {
    }

    private ContainerHolders() {
    }

    static @Nullable Target resolve(@Nullable InventoryHolder holder) {
        if (holder instanceof Container blockContainer) {
            Location loc = blockContainer.getBlock().getLocation();
            return new Target(BlockLocations.fromLocation(loc),
                    blockContainer.getBlock().getType().name());
        }
        if (holder instanceof StorageMinecart cart) {
            return entityTarget(cart);
        }
        if (holder instanceof HopperMinecart cart) {
            return entityTarget(cart);
        }
        if (holder instanceof AbstractHorse horse) {
            return entityTarget(horse);
        }
        if (holder instanceof ChestBoat boat) {
            return entityTarget(boat);
        }
        return null;
    }

    static Target entityTarget(Entity entity) {
        return new Target(
                BlockLocations.fromLocation(entity.getLocation()),
                entity.getType().name());
    }
}
