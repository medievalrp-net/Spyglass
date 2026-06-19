package net.medievalrp.spyglass.plugin.salvage;

import java.util.List;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * Marks a Bukkit inventory as one of the salvage GUIs so the click listener can
 * recognise it (and tell the index grid from an open snapshot). Carries the
 * context each view needs: the index grid holds the snapshots it lists (slot →
 * snapshot); an open snapshot holds its id so extractions persist back to it.
 */
public final class SalvageHolder implements InventoryHolder {

    public enum Kind { INDEX, SNAPSHOT }

    private final Kind kind;
    private final SalvageSnapshot snapshot;
    private final List<SalvageSnapshot> snapshots;
    private Inventory inventory;

    private SalvageHolder(Kind kind, SalvageSnapshot snapshot, List<SalvageSnapshot> snapshots) {
        this.kind = kind;
        this.snapshot = snapshot;
        this.snapshots = snapshots;
    }

    static SalvageHolder index(List<SalvageSnapshot> snapshots) {
        return new SalvageHolder(Kind.INDEX, null, snapshots);
    }

    static SalvageHolder snapshot(SalvageSnapshot snapshot) {
        return new SalvageHolder(Kind.SNAPSHOT, snapshot, List.of());
    }

    public Kind kind() {
        return kind;
    }

    /** The snapshot this view shows (SNAPSHOT kind only; null for INDEX). */
    public SalvageSnapshot snapshot() {
        return snapshot;
    }

    public List<SalvageSnapshot> snapshots() {
        return snapshots;
    }

    void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
