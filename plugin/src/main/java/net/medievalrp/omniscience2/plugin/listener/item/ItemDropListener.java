package net.medievalrp.omniscience2.plugin.listener.item;

import java.util.Set;
import net.medievalrp.omniscience2.api.event.ItemDropRecord;
import net.medievalrp.omniscience2.api.event.RecordContext;
import net.medievalrp.omniscience2.api.event.StoredItem;
import net.medievalrp.omniscience2.api.util.BlockLocation;
import net.medievalrp.omniscience2.plugin.listener.RecordingSupport;
import net.medievalrp.omniscience2.plugin.listener.RecordingListener;
import net.medievalrp.omniscience2.plugin.pipeline.Recorder;
import net.medievalrp.omniscience2.plugin.util.BlockLocations;
import net.medievalrp.omniscience2.plugin.util.ItemSerialization;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
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
}
