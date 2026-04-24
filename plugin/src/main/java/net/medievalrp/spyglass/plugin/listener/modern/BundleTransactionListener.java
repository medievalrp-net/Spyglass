package net.medievalrp.spyglass.plugin.listener.modern;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.medievalrp.spyglass.api.event.ContainerDepositRecord;
import net.medievalrp.spyglass.api.event.ContainerWithdrawRecord;
import net.medievalrp.spyglass.api.event.StoredItem;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.plugin.listener.RecordingListener;
import net.medievalrp.spyglass.plugin.listener.RecordingSupport;
import net.medievalrp.spyglass.plugin.pipeline.Recorder;
import net.medievalrp.spyglass.plugin.util.BlockLocations;
import net.medievalrp.spyglass.plugin.util.ItemSerialization;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.ApiStatus;

/**
 * Records item insert/extract actions against bundles. Bundles have no
 * dedicated "modify" event in the Bukkit API, so we snapshot each involved
 * bundle's {@link BundleMeta#getItems()} at click time and defer one tick to
 * diff against the post-click state, emitting one record per material-delta.
 */
@ApiStatus.Internal
public final class BundleTransactionListener implements RecordingListener {

    private final Recorder recorder;
    private final RecordingSupport support;
    private final JavaPlugin plugin;

    public BundleTransactionListener(Recorder recorder, RecordingSupport support, JavaPlugin plugin) {
        this.recorder = recorder;
        this.support = support;
        this.plugin = plugin;
    }

    @Override
    public Set<String> events() {
        return Set.of("bundle-insert", "bundle-extract");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        ItemStack slotItem = event.getCurrentItem();
        ItemStack cursor = event.getCursor();

        Snapshot slotSnap = snapshotOf(slotItem);
        Snapshot cursorSnap = snapshotOf(cursor);
        if (slotSnap == null && cursorSnap == null) {
            return;
        }

        int slotIndex = event.getSlot();
        int rawSlot = event.getRawSlot();
        plugin.getLogger().info("bundle-click: action=" + event.getAction()
                + " slotBundle=" + (slotSnap != null)
                + " cursorBundle=" + (cursorSnap != null)
                + " rawSlot=" + rawSlot);

        Bukkit.getScheduler().runTask(plugin, () -> {
            if (slotSnap != null) {
                ItemStack afterStack = rawSlot >= 0 ? event.getView().getItem(rawSlot) : null;
                plugin.getLogger().info("bundle-diff (slot): beforeItems=" + slotSnap.items.size()
                        + " afterStack=" + (afterStack == null ? "null" : afterStack.getType()));
                diffAndEmit(player, slotSnap, slotIndex, afterStack);
            }
            if (cursorSnap != null) {
                ItemStack afterCursor = player.getItemOnCursor();
                plugin.getLogger().info("bundle-diff (cursor): beforeItems=" + cursorSnap.items.size()
                        + " afterCursor=" + (afterCursor == null ? "null" : afterCursor.getType()));
                diffAndEmit(player, cursorSnap, -1, afterCursor);
            }
        });
    }

    private static Snapshot snapshotOf(ItemStack stack) {
        if (stack == null || stack.getType() == Material.AIR) {
            return null;
        }
        // hasItemMeta() returns false on default-component bundles in some Paper
        // builds, so go straight to getItemMeta() and instanceof-check.
        ItemMeta meta = stack.getItemMeta();
        if (!(meta instanceof BundleMeta bundleMeta)) {
            return null;
        }
        List<ItemStack> items = new ArrayList<>();
        for (ItemStack inner : bundleMeta.getItems()) {
            if (inner != null) {
                items.add(inner.clone());
            }
        }
        return new Snapshot(stack.getType(), items);
    }

    private void diffAndEmit(Player player, Snapshot before, int slot, ItemStack afterStack) {
        List<ItemStack> afterItems = List.of();
        if (afterStack != null && afterStack.getType() == before.material
                && afterStack.getItemMeta() instanceof BundleMeta bm) {
            afterItems = bm.getItems();
        }

        Map<Material, Integer> delta = new HashMap<>();
        for (ItemStack item : before.items) {
            delta.merge(item.getType(), -item.getAmount(), Integer::sum);
        }
        for (ItemStack item : afterItems) {
            if (item != null) {
                delta.merge(item.getType(), item.getAmount(), Integer::sum);
            }
        }

        BlockLocation location = BlockLocations.fromLocation(player.getLocation());
        String bundleType = before.material.name();
        Instant occurred = support.now();

        for (Map.Entry<Material, Integer> entry : delta.entrySet()) {
            int d = entry.getValue();
            if (d == 0) {
                continue;
            }
            Material material = entry.getKey();
            StoredItem stored = ItemSerialization.storedItem(0, new ItemStack(material, Math.abs(d)));
            if (d > 0) {
                recorder.record(new ContainerDepositRecord(
                        support.newId(), 1, "bundle-insert", occurred,
                        support.expiresAt(occurred),
                        support.playerOrigin(), support.playerSource(player),
                        location, material.name(), bundleType, slot, d, null, stored));
            } else {
                recorder.record(new ContainerWithdrawRecord(
                        support.newId(), 1, "bundle-extract", occurred,
                        support.expiresAt(occurred),
                        support.playerOrigin(), support.playerSource(player),
                        location, material.name(), bundleType, slot, -d, stored, null));
            }
        }
    }

    private record Snapshot(Material material, List<ItemStack> items) {
    }
}
