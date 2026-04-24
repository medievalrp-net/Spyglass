package net.medievalrp.omniscience2.plugin.listener.modern;

import java.util.Set;
import net.medievalrp.omniscience2.api.event.ContainerWithdrawRecord;
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
import org.bukkit.event.block.CrafterCraftEvent;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class CrafterListener implements RecordingListener {

    private final Recorder recorder;
    private final RecordingSupport support;

    public CrafterListener(Recorder recorder, RecordingSupport support) {
        this.recorder = recorder;
        this.support = support;
    }

    @Override
    public Set<String> events() {
        return Set.of("crafter");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCrafterCraft(CrafterCraftEvent event) {
        ItemStack result = event.getResult();
        if (result == null) {
            return;
        }
        BlockLocation location = BlockLocations.fromLocation(event.getBlock().getLocation());
        StoredItem stored = ItemSerialization.storedItem(0, result);
        RecordContext ctx = support.environmentContext("crafter", location);
        recorder.record(ContainerWithdrawRecord.of(ctx, "crafter", result.getType().name(),
                "CRAFTER", 0, result.getAmount(), stored, null));
    }
}
