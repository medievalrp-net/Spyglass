package net.medievalrp.spyglass.plugin.listener.item;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executor;
import net.medievalrp.spyglass.api.event.ItemDropRecord;
import net.medievalrp.spyglass.api.event.ItemPickupRecord;
import net.medievalrp.spyglass.api.event.RecordContext;
import net.medievalrp.spyglass.api.event.StoredItem;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.plugin.listener.RecordingListener;
import net.medievalrp.spyglass.plugin.listener.RecordingSupport;
import net.medievalrp.spyglass.plugin.pipeline.Recorder;
import net.medievalrp.spyglass.plugin.util.BlockLocations;
import net.medievalrp.spyglass.plugin.util.ItemSerialization;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;

/**
 * Logs automated container-to-container item movement by a hopper, dropper or
 * dispenser ({@code InventoryMoveItemEvent}) - CoreProtect's {@code
 * hopper-transactions} parity (#226). Player container access is handled by
 * {@link net.medievalrp.spyglass.plugin.listener.container.ContainerTransactionListener};
 * this is the automated flow that had no listener before.
 *
 * <p>Both endpoints are recorded so inspecting either container surfaces the
 * movement: {@code transfer-out} on the source (items left it, an {@link
 * ItemDropRecord}) and {@code transfer-in} on the destination (items arrived,
 * an {@link ItemPickupRecord}). Both are forensic-only, never rolled back - an
 * area rollback must not try to reverse thousands of hopper ticks, and the
 * records reuse the informational item shapes so no store change is needed.
 *
 * <p>This event fires on the server thread on every hopper tick, so the
 * handler only takes a bounded snapshot inline (clone the moved stack, read
 * the two block coordinates and the mover type). The {@link TransferDedup}
 * check, the item projection, the record build and {@code recorder.record}
 * all run off-main on the injected serializer, matching {@link
 * ItemPickupListener}.
 */
@ApiStatus.Internal
public final class HopperTransferListener implements RecordingListener {

    private final Recorder recorder;
    private final RecordingSupport support;
    private final Executor serializer;
    // Per-event gating: the plugin gates only at listener-registration
    // granularity, so the independent transfer-in / transfer-out toggles must
    // be honoured here. Live, thread-safe view of the enabled set.
    private final Set<String> enabledEvents;
    private final TransferDedup dedup;

    public HopperTransferListener(Recorder recorder, RecordingSupport support, Executor serializer,
            Set<String> enabledEvents, TransferDedup dedup) {
        this.recorder = recorder;
        this.support = support;
        this.serializer = serializer;
        this.enabledEvents = enabledEvents;
        this.dedup = dedup;
    }

    @Override
    public Set<String> events() {
        return Set.of("transfer-in", "transfer-out");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryMoveItem(InventoryMoveItemEvent event) {
        boolean logOut = enabledEvents.contains("transfer-out");
        boolean logIn = enabledEvents.contains("transfer-in");
        if (!logOut && !logIn) {
            return;
        }
        ItemStack item = event.getItem();
        if (item == null || item.getType() == Material.AIR) {
            return;
        }
        Location sourceLoc = event.getSource().getLocation();
        Location destLoc = event.getDestination().getLocation();
        if (sourceLoc == null || destLoc == null) {
            // A virtual inventory with no world position: nothing to attribute.
            return;
        }
        // Cheap snapshot on the server thread: clone the moved stack (the live
        // one is about to be transferred and may be merged/mutated), read the
        // two block coordinates and the mover mechanic. Everything expensive
        // runs off-main below.
        ItemStack snapshot = item.clone();
        String material = item.getType().name();
        int amount = item.getAmount();
        // The mover mechanic (hopper / dropper / dispenser) for attribution.
        // Guarded because a mocked or unusual inventory can report a null type.
        InventoryType initiatorType = event.getInitiator().getType();
        String mover = initiatorType != null
                ? initiatorType.name().toLowerCase(Locale.ROOT)
                : "hopper";
        BlockLocation source = BlockLocations.fromLocation(sourceLoc);
        BlockLocation dest = BlockLocations.fromLocation(destLoc);
        TransferDedup.Key key = new TransferDedup.Key(
                source.worldName(), source.x(), source.y(), source.z(),
                dest.x(), dest.y(), dest.z(), material);

        serializer.execute(() -> {
            if (!dedup.shouldLog(key)) {
                return;      // steady farm flow already logged this window
            }
            StoredItem stored = ItemSerialization.storedItemProjection(0, snapshot);
            if (stored == null) {
                return;
            }
            if (logOut) {
                RecordContext ctx = support.context(
                        support.environmentOrigin("transfer:" + mover),
                        support.environmentSource(mover), source);
                recorder.record(ItemDropRecord.of(ctx, "transfer-out", material, amount, stored));
            }
            if (logIn) {
                RecordContext ctx = support.context(
                        support.environmentOrigin("transfer:" + mover),
                        support.environmentSource(mover), dest);
                recorder.record(ItemPickupRecord.of(ctx, "transfer-in", material, amount, stored));
            }
        });
    }
}
