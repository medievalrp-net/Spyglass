package net.medievalrp.spyglass.plugin.listener.item;

import java.util.Set;
import net.medievalrp.spyglass.api.event.ItemDropRecord;
import net.medievalrp.spyglass.api.event.Origin;
import net.medievalrp.spyglass.api.event.RecordContext;
import net.medievalrp.spyglass.api.event.Source;
import net.medievalrp.spyglass.api.event.StoredItem;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.plugin.listener.RecordingSupport;
import net.medievalrp.spyglass.plugin.listener.RecordingListener;
import net.medievalrp.spyglass.plugin.pipeline.Recorder;
import net.medievalrp.spyglass.plugin.util.BlockLocations;
import net.medievalrp.spyglass.plugin.util.ItemSerialization;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.entity.EntityDropItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class ItemDropListener implements RecordingListener {

    private final Recorder recorder;
    private final RecordingSupport support;

    public ItemDropListener(Recorder recorder, RecordingSupport support) {
        this.recorder = recorder;
        this.support = support;
    }

    @Override
    public Set<String> events() {
        return Set.of("drop");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        ItemStack stack = event.getItemDrop().getItemStack();
        StoredItem stored = ItemSerialization.storedItem(0, stack);
        if (stored == null) {
            return;
        }
        BlockLocation location = BlockLocations.fromLocation(event.getPlayer().getLocation());
        RecordContext ctx = support.playerContext(event.getPlayer(), location);
        recorder.record(ItemDropRecord.of(ctx, stack.getType().name(), stack.getAmount(), stored));
    }

    /**
     * Dispensers and droppers firing items into the world. Attributed
     * to the environment with the block material in the origin detail
     * ("dispenser", "dropper"). Without this, a dispenser trap's
     * output left no trace in the log.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockDispense(BlockDispenseEvent event) {
        ItemStack stack = event.getItem();
        StoredItem stored = ItemSerialization.storedItem(0, stack);
        if (stored == null) {
            return;
        }
        Block block = event.getBlock();
        String blockName = block.getType().name();
        BlockLocation location = BlockLocations.fromLocation(block.getLocation());
        Origin origin = support.environmentOrigin(blockName.toLowerCase(java.util.Locale.ROOT));
        Source source = support.environmentSource(blockName.toLowerCase(java.util.Locale.ROOT));
        RecordContext ctx = support.context(origin, source, location);
        recorder.record(ItemDropRecord.of(ctx, stack.getType().name(), stack.getAmount(), stored));
    }

    /**
     * Non-player entity drops (a zombie dropping its pickup item, an
     * allay releasing a held stack, etc.). Player drops go through
     * {@link #onPlayerDropItem(PlayerDropItemEvent)} which fires on a
     * different event, so this path filters Player out to avoid
     * duplicate records.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDropItem(EntityDropItemEvent event) {
        if (event.getEntity() instanceof Player) {
            return;
        }
        ItemStack stack = event.getItemDrop().getItemStack();
        StoredItem stored = ItemSerialization.storedItem(0, stack);
        if (stored == null) {
            return;
        }
        String entityType = event.getEntity().getType().getKey().getKey();
        BlockLocation location = BlockLocations.fromLocation(event.getEntity().getLocation());
        Origin origin = support.environmentOrigin("entity-drop:" + entityType);
        Source source = support.entitySource(event.getEntity().getUniqueId(), entityType);
        RecordContext ctx = support.context(origin, source, location);
        recorder.record(ItemDropRecord.of(ctx, stack.getType().name(), stack.getAmount(), stored));
    }
}
