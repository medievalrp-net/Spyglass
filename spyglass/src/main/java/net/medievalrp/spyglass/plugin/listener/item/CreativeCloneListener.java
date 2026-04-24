package net.medievalrp.spyglass.plugin.listener.item;

import java.util.Set;
import net.medievalrp.spyglass.api.event.ItemPickupRecord;
import net.medievalrp.spyglass.api.event.RecordContext;
import net.medievalrp.spyglass.api.event.StoredItem;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.plugin.listener.RecordingListener;
import net.medievalrp.spyglass.plugin.listener.RecordingSupport;
import net.medievalrp.spyglass.plugin.pipeline.Recorder;
import net.medievalrp.spyglass.plugin.util.BlockLocations;
import net.medievalrp.spyglass.plugin.util.ItemSerialization;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;

/**
 * A creative-mode player middle-clicked an item slot, gaining a free
 * copy onto the cursor. Logged as a crumb trail for investigating
 * item-duplication reports — if an operator sees an {@code iron_sword}
 * stack of 64 appear out of thin air in survival inventories, the
 * prior {@code clone} records show which creative-mode player sourced
 * the item.
 *
 * <p>Emitted as {@link ItemPickupRecord} with event name {@code clone}
 * so it shares the item-search path with regular pickups; separating
 * into a bespoke record type would duplicate the schema with no extra
 * information.
 */
@ApiStatus.Internal
public final class CreativeCloneListener implements RecordingListener {

    private final Recorder recorder;
    private final RecordingSupport support;

    public CreativeCloneListener(Recorder recorder, RecordingSupport support) {
        this.recorder = recorder;
        this.support = support;
    }

    @Override
    public Set<String> events() {
        return Set.of("clone");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getAction() != InventoryAction.CLONE_STACK) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        ItemStack source = event.getCurrentItem();
        if (source == null || source.getType() == Material.AIR) {
            return;
        }
        StoredItem stored = ItemSerialization.storedItem(0, source);
        if (stored == null) {
            return;
        }
        BlockLocation location = BlockLocations.fromLocation(player.getLocation());
        RecordContext ctx = support.playerContext(player, location);
        // Reuse ItemPickupRecord's shape but stamp the event as "clone"
        // so queries separate the two. The canonical constructor avoids
        // the factory's hard-coded "pickup" event name.
        recorder.record(new ItemPickupRecord(
                ctx.id(),
                ctx.schemaVersion(),
                "clone",
                ctx.occurred(),
                ctx.expiresAt(),
                ctx.origin(),
                ctx.source(),
                ctx.location(),
                source.getType().name(),
                source.getAmount(),
                stored));
    }
}
