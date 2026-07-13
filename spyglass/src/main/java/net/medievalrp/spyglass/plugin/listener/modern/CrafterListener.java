package net.medievalrp.spyglass.plugin.listener.modern;

import java.util.Set;
import java.util.concurrent.Executor;
import net.medievalrp.spyglass.api.event.ContainerWithdrawRecord;
import net.medievalrp.spyglass.api.event.RecordContext;
import net.medievalrp.spyglass.api.event.StoredItem;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.plugin.listener.RecordingSupport;
import net.medievalrp.spyglass.plugin.listener.RecordingListener;
import net.medievalrp.spyglass.plugin.pipeline.Recorder;
import net.medievalrp.spyglass.plugin.util.BlockLocations;
import net.medievalrp.spyglass.plugin.util.ItemSerialization;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.CrafterCraftEvent;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;

/**
 * Records crafter (1.21+ auto-crafting block) craft results. A crafter farm
 * fires this once per pulse per crafter, so the handler only takes a cheap
 * snapshot on the server thread: {@code getResult()} already hands back a
 * defensive clone, and the context is minted at event time to keep id and
 * timestamp ordering. The {@code serializeAsBytes()} blob build and the
 * record hand-off run on the injected serializer (#98 pattern, #327). The
 * record is a rollbackable {@link ContainerWithdrawRecord}, so the full item
 * blob stays and the serializer's flush barrier keeps read-your-writes for
 * rollback.
 */
@ApiStatus.Internal
public final class CrafterListener implements RecordingListener {

    private final Recorder recorder;
    private final RecordingSupport support;
    private final Executor serializer;

    public CrafterListener(Recorder recorder, RecordingSupport support, Executor serializer) {
        this.recorder = recorder;
        this.support = support;
        this.serializer = serializer;
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
        RecordContext ctx = support.environmentContext("crafter", location);
        String material = result.getType().name();
        int amount = result.getAmount();
        serializer.execute(() -> {
            StoredItem stored = ItemSerialization.storedItem(0, result);
            recorder.record(ContainerWithdrawRecord.of(ctx, "crafter", material,
                    "CRAFTER", 0, amount, stored, null));
        });
    }
}
