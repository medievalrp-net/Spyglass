package net.medievalrp.spyglass.plugin.salvage;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.logging.Logger;
import net.medievalrp.spyglass.api.event.StoredItem;
import net.medievalrp.spyglass.plugin.rollback.SalvageHook;
import net.medievalrp.spyglass.plugin.util.ItemSerialization;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Captures container inventories a rollback destroys (see {@link SalvageHook}).
 *
 * <p>On {@link #onChunkResolved} it clones the live contents of every non-empty
 * container in the chunk (the chunk's block-entity map lists them directly — no
 * per-block scan; terrain chunks have none and cost nothing). On
 * {@link #onChunkWritten} it re-reads each captured cell: if the container is
 * gone or its contents changed, the rollback destroyed it and the clone is
 * persisted; if it survived unchanged, the clone is dropped. So only genuinely
 * destroyed inventories reach the store, and nothing still present in the world
 * is ever salvaged.
 *
 * <p>All hook methods run on the main thread; only the final {@link SalvageStore#save}
 * is dispatched off-main. One rollback runs at a time (the job queue serializes
 * them), so the per-run {@code pending} state needs no cross-run synchronization.
 */
public final class SalvageCapturer implements SalvageHook {

    private final SalvageStore store;
    private final Executor persistExecutor;
    private final Logger logger;

    private final Map<String, List<Captured>> pending = new HashMap<>();
    // Cells already salvaged this rollback — guards against a chunk being
    // resolved twice (its simple cells via the columnar path, its complex cells
    // via the object path) double-persisting the same destroyed container.
    private final Set<String> salvagedCells = new HashSet<>();
    private volatile String operator = "console";
    private volatile UUID rollbackId = new UUID(0L, 0L);

    public SalvageCapturer(SalvageStore store, Executor persistExecutor, Logger logger) {
        this.store = store;
        this.persistExecutor = persistExecutor;
        this.logger = logger;
    }

    private record Captured(int x, int y, int z, String type, List<StoredItem> items) {
    }

    @Override
    public void begin(String operatorName, UUID rollbackId) {
        this.operator = operatorName == null ? "console" : operatorName;
        this.rollbackId = rollbackId == null ? new UUID(0L, 0L) : rollbackId;
        this.pending.clear();
        this.salvagedCells.clear();
    }

    @Override
    public void end() {
        this.pending.clear();
        this.salvagedCells.clear();
        this.operator = "console";
    }

    @Override
    public void onChunkResolved(World world, int chunkX, int chunkZ) {
        if (!world.isChunkLoaded(chunkX, chunkZ)) {
            return;
        }
        Chunk chunk = world.getChunkAt(chunkX, chunkZ);
        List<Captured> captured = null;
        // useSnapshot=false: read the live tile entities (we're on the main
        // thread, before any write to this chunk). Most chunks have none.
        for (BlockState state : chunk.getTileEntities(false)) {
            if (state instanceof Container container) {
                List<StoredItem> items = capture(inventoryOf(container));
                if (!items.isEmpty()) {
                    if (captured == null) {
                        captured = new ArrayList<>();
                    }
                    captured.add(new Captured(state.getX(), state.getY(), state.getZ(),
                            state.getType().name(), items));
                }
            }
        }
        if (captured != null) {
            pending.put(key(world, chunkX, chunkZ), captured);
            logger.fine("salvage: captured " + captured.size()
                    + " non-empty container(s) in chunk " + chunkX + "," + chunkZ);
        }
    }

    @Override
    public void onChunkWritten(World world, int chunkX, int chunkZ) {
        List<Captured> captured = pending.remove(key(world, chunkX, chunkZ));
        if (captured == null) {
            return;
        }
        final String op = operator;
        List<SalvageSnapshot> toSave = new ArrayList<>();
        for (Captured cap : captured) {
            Block block = world.getBlockAt(cap.x(), cap.y(), cap.z());
            boolean destroyed;
            // Check the block TYPE first — read from the chunk section the
            // rollback overwrote. A direct NMS section write leaves the old block
            // entity lingering in the chunk map, so getState() can still return a
            // stale Container even though the block is now (e.g.) stone; the
            // material is authoritative.
            if (!block.getType().name().equals(cap.type())) {
                destroyed = true;
            } else if (block.getState() instanceof Container container) {
                // Same container type still here — destroyed only if its contents
                // changed (e.g. the rollback restored a different recorded inv).
                destroyed = !sameItems(capture(inventoryOf(container)), cap.items());
            } else {
                destroyed = true;
            }
            if (destroyed && salvagedCells.add(
                    world.getUID() + ":" + cap.x() + ":" + cap.y() + ":" + cap.z())) {
                toSave.add(new SalvageSnapshot(UUID.randomUUID(), rollbackId,
                        world.getUID(), world.getName(),
                        cap.x(), cap.y(), cap.z(), cap.type(), op,
                        Instant.now(), cap.items()));
            }
        }
        if (!toSave.isEmpty()) {
            logger.fine("salvage: persisting " + toSave.size()
                    + " destroyed container(s) from chunk " + chunkX + "," + chunkZ);
            persistExecutor.execute(() -> {
                for (SalvageSnapshot snapshot : toSave) {
                    try {
                        store.save(snapshot);
                    } catch (RuntimeException ex) {
                        logger.warning("Spyglass salvage save failed at " + snapshot.x()
                                + "," + snapshot.y() + "," + snapshot.z() + ": " + ex.getMessage());
                    }
                }
            });
        }
    }

    // A chest that is half of a double chest reports the COMBINED 54-slot
    // inventory via getInventory(); the per-block inventory keeps each half its
    // own 27-slot snapshot so a double chest is never captured (recovered) twice.
    private static Inventory inventoryOf(Container container) {
        return container instanceof org.bukkit.block.Chest chest
                ? chest.getBlockInventory() : container.getInventory();
    }

    private static List<StoredItem> capture(Inventory inventory) {
        List<StoredItem> items = new ArrayList<>();
        int size = inventory.getSize();
        for (int slot = 0; slot < size; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack != null && stack.getType() != Material.AIR) {
                items.add(ItemSerialization.storedItem(slot, stack));
            }
        }
        return items;
    }

    // Equal iff the same slot->blob set. Order-independent; cheap for the
    // handful of captured containers.
    private static boolean sameItems(List<StoredItem> a, List<StoredItem> b) {
        if (a.size() != b.size()) {
            return false;
        }
        Set<String> keys = new HashSet<>();
        for (StoredItem item : a) {
            keys.add(item.slot() + ":" + item.data());
        }
        for (StoredItem item : b) {
            if (!keys.contains(item.slot() + ":" + item.data())) {
                return false;
            }
        }
        return true;
    }

    private static String key(World world, int chunkX, int chunkZ) {
        return world.getUID() + ":" + chunkX + ":" + chunkZ;
    }
}
