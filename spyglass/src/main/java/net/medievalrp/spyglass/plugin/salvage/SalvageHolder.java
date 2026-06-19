package net.medievalrp.spyglass.plugin.salvage;

import java.util.List;
import java.util.UUID;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * Marks a Bukkit inventory as one of the salvage GUI levels and carries the
 * navigation state the click handler needs: the top level lists rollbacks; a
 * rollback level lists that rollback's destroyed containers; a container level
 * shows its items. {@code page} drives pagination at every level.
 */
public final class SalvageHolder implements InventoryHolder {

    public enum Kind { ROLLBACKS, CHESTS, ITEMS }

    private final Kind kind;
    private final int page;
    private final List<SalvageStore.RollbackGroup> rollbacks; // ROLLBACKS
    private final UUID rollbackId;                            // CHESTS, ITEMS (parent)
    private final List<SalvageSnapshot> snapshots;            // CHESTS
    private final SalvageSnapshot snapshot;                   // ITEMS
    private Inventory inventory;

    private SalvageHolder(Kind kind, int page, List<SalvageStore.RollbackGroup> rollbacks,
                          UUID rollbackId, List<SalvageSnapshot> snapshots, SalvageSnapshot snapshot) {
        this.kind = kind;
        this.page = page;
        this.rollbacks = rollbacks;
        this.rollbackId = rollbackId;
        this.snapshots = snapshots;
        this.snapshot = snapshot;
    }

    static SalvageHolder rollbacks(List<SalvageStore.RollbackGroup> groups, int page) {
        return new SalvageHolder(Kind.ROLLBACKS, page, groups, null, null, null);
    }

    static SalvageHolder chests(UUID rollbackId, List<SalvageSnapshot> snapshots, int page) {
        return new SalvageHolder(Kind.CHESTS, page, null, rollbackId, snapshots, null);
    }

    static SalvageHolder items(UUID rollbackId, SalvageSnapshot snapshot, int page) {
        return new SalvageHolder(Kind.ITEMS, page, null, rollbackId, null, snapshot);
    }

    public Kind kind() {
        return kind;
    }

    public int page() {
        return page;
    }

    public List<SalvageStore.RollbackGroup> rollbacks() {
        return rollbacks;
    }

    public UUID rollbackId() {
        return rollbackId;
    }

    public List<SalvageSnapshot> snapshots() {
        return snapshots;
    }

    public SalvageSnapshot snapshot() {
        return snapshot;
    }

    void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
