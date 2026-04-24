package net.medievalrp.spyglass.plugin.listener.block;

import java.util.Set;
import net.medievalrp.spyglass.api.event.ItemDropRecord;
import net.medievalrp.spyglass.api.event.RecordContext;
import net.medievalrp.spyglass.api.event.StoredItem;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.plugin.listener.RecordingSupport;
import net.medievalrp.spyglass.plugin.listener.RecordingListener;
import net.medievalrp.spyglass.plugin.pipeline.Recorder;
import net.medievalrp.spyglass.plugin.util.BlockLocations;
import net.medievalrp.spyglass.plugin.util.ItemSerialization;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;

/**
 * Logs each item that falls from a broken container as its own {@code drop}
 * record. The chest's own break already carries the inventory snapshot, but
 * operators typically want to see the drops inline in search output.
 */
@ApiStatus.Internal
public final class ContainerDropListener implements RecordingListener {

    private final Recorder recorder;
    private final RecordingSupport support;

    public ContainerDropListener(Recorder recorder, RecordingSupport support) {
        this.recorder = recorder;
        this.support = support;
    }

    @Override
    public Set<String> events() {
        return Set.of("drop");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockDropItem(BlockDropItemEvent event) {
        BlockState state = event.getBlockState();
        if (!(state instanceof Container)) {
            return;
        }
        Player player = event.getPlayer();
        BlockLocation location = BlockLocations.fromLocation(state.getLocation());

        int syntheticSlot = 0;
        for (Item itemEntity : event.getItems()) {
            ItemStack stack = itemEntity.getItemStack();
            if (stack == null || stack.getAmount() <= 0) {
                continue;
            }
            StoredItem stored = ItemSerialization.storedItem(syntheticSlot++, stack);
            RecordContext ctx = support.playerContext(player, location);
            recorder.record(ItemDropRecord.of(ctx, stack.getType().name(), stack.getAmount(), stored));
        }
    }
}
