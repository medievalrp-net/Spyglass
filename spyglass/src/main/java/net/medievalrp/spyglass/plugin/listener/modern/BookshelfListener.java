package net.medievalrp.spyglass.plugin.listener.modern;

import java.util.Set;
import net.medievalrp.spyglass.api.event.ContainerDepositRecord;
import net.medievalrp.spyglass.api.event.ContainerWithdrawRecord;
import net.medievalrp.spyglass.api.event.RecordContext;
import net.medievalrp.spyglass.api.event.StoredItem;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.plugin.listener.RecordingSupport;
import net.medievalrp.spyglass.plugin.listener.RecordingListener;
import net.medievalrp.spyglass.plugin.pipeline.Recorder;
import net.medievalrp.spyglass.plugin.util.BlockLocations;
import net.medievalrp.spyglass.plugin.util.ItemSerialization;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.ChiseledBookshelf;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ChiseledBookshelfInventory;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class BookshelfListener implements RecordingListener {

    private final Recorder recorder;
    private final RecordingSupport support;

    public BookshelfListener(Recorder recorder, RecordingSupport support) {
        this.recorder = recorder;
        this.support = support;
    }

    @Override
    public Set<String> events() {
        return Set.of("bookshelf-insert", "bookshelf-remove");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null || block.getType() != Material.CHISELED_BOOKSHELF) {
            return;
        }
        if (!(block.getState(false) instanceof ChiseledBookshelf state)) {
            return;
        }
        if (event.getClickedPosition() == null) {
            return;
        }
        int slot;
        try {
            slot = state.getSlot(event.getClickedPosition());
        } catch (RuntimeException ex) {
            return;
        }
        if (slot < 0) {
            return;
        }
        ChiseledBookshelfInventory inventory = state.getSnapshotInventory();
        ItemStack existing = inventory.getItem(slot);
        ItemStack inHand = event.getPlayer().getInventory().getItemInMainHand();
        boolean hasExisting = existing != null && existing.getType() != Material.AIR;
        boolean hasInHand = inHand != null && isBook(inHand.getType());
        Player player = event.getPlayer();
        BlockLocation location = BlockLocations.fromLocation(block.getLocation());

        if (hasExisting) {
            StoredItem removed = ItemSerialization.storedItem(slot, existing);
            RecordContext ctx = support.playerContext(player, location);
            recorder.record(ContainerWithdrawRecord.of(ctx, "bookshelf-remove", existing.getType().name(),
                    "CHISELED_BOOKSHELF", slot, existing.getAmount(), removed, null));
            return;
        }
        if (hasInHand) {
            StoredItem inserted = ItemSerialization.storedItem(slot, inHand);
            RecordContext ctx = support.playerContext(player, location);
            recorder.record(ContainerDepositRecord.of(ctx, "bookshelf-insert", inHand.getType().name(),
                    "CHISELED_BOOKSHELF", slot, 1, null, inserted));
        }
    }

    private static boolean isBook(Material material) {
        return material == Material.BOOK
                || material == Material.WRITABLE_BOOK
                || material == Material.WRITTEN_BOOK
                || material == Material.ENCHANTED_BOOK
                || material == Material.KNOWLEDGE_BOOK;
    }
}
