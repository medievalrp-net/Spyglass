package net.medievalrp.spyglass.plugin.listener.entity;

import java.util.Set;
import net.medievalrp.spyglass.api.event.ContainerDepositRecord;
import net.medievalrp.spyglass.api.event.ContainerWithdrawRecord;
import net.medievalrp.spyglass.api.event.RecordContext;
import net.medievalrp.spyglass.api.event.StoredItem;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.plugin.listener.RecordingListener;
import net.medievalrp.spyglass.plugin.listener.RecordingSupport;
import net.medievalrp.spyglass.plugin.pipeline.Recorder;
import net.medievalrp.spyglass.plugin.util.BlockLocations;
import net.medievalrp.spyglass.plugin.util.ItemSerialization;
import org.bukkit.Material;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;

/**
 * Logs items placed into and removed from item frames. v1's
 * {@code EventEntityItemListener} covered both armor stands and
 * item frames; wave-7's {@code ArmorStandManipulateListener} only
 * covered the armor-stand half, silently dropping every item-frame
 * interaction.
 *
 * <p>Two events to hook:
 * <ul>
 *   <li>{@link PlayerInteractEntityEvent} — right-click with an item
 *       while the frame is empty → <strong>entity-deposit</strong>.
 *   <li>{@link EntityDamageByEntityEvent} — player left-clicks the
 *       frame (= "damage" it) while it holds an item → the frame
 *       drops its content → <strong>entity-withdraw</strong>.
 * </ul>
 *
 * <p>Rotations and frame-breaks are not logged; the operator-interesting
 * signal is "what item went in/out", not "player nudged the compass".
 */
@ApiStatus.Internal
public final class ItemFrameInteractListener implements RecordingListener {

    private final Recorder recorder;
    private final RecordingSupport support;

    public ItemFrameInteractListener(Recorder recorder, RecordingSupport support) {
        this.recorder = recorder;
        this.support = support;
    }

    @Override
    public Set<String> events() {
        return Set.of("entity-deposit", "entity-withdraw");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteractFrame(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof ItemFrame frame)) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack inHand = player.getInventory().getItem(
                event.getHand() == EquipmentSlot.OFF_HAND
                        ? EquipmentSlot.OFF_HAND : EquipmentSlot.HAND);
        // Deposit fires only if the frame was previously empty and the
        // player's hand carries something. Rotating an already-held item
        // also dispatches PlayerInteractEntityEvent but frame.getItem()
        // returns the held item, not AIR, so this check filters rotations
        // out.
        if (!isEmpty(frame.getItem())) {
            return;
        }
        if (isEmpty(inHand)) {
            return;
        }
        BlockLocation location = BlockLocations.fromLocation(frame.getLocation());
        StoredItem after = ItemSerialization.storedItem(0, inHand);
        RecordContext ctx = support.playerContext(player, location);
        recorder.record(ContainerDepositRecord.of(ctx,
                "entity-deposit", inHand.getType().name(), "ITEM_FRAME",
                0, 1, null, after));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamageFrame(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof ItemFrame frame)) {
            return;
        }
        if (!(event.getDamager() instanceof Player player)) {
            return;
        }
        ItemStack held = frame.getItem();
        if (isEmpty(held)) {
            return;
        }
        BlockLocation location = BlockLocations.fromLocation(frame.getLocation());
        StoredItem before = ItemSerialization.storedItem(0, held);
        RecordContext ctx = support.playerContext(player, location);
        recorder.record(ContainerWithdrawRecord.of(ctx,
                "entity-withdraw", held.getType().name(), "ITEM_FRAME",
                0, held.getAmount(), before, null));
    }

    private static boolean isEmpty(ItemStack stack) {
        return stack == null || stack.getType() == Material.AIR;
    }
}
