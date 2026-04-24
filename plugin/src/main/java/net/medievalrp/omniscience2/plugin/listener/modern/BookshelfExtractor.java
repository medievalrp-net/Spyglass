package net.medievalrp.omniscience2.plugin.listener.modern;

import java.util.Set;
import java.util.stream.Stream;
import net.medievalrp.omniscience2.api.event.ContainerDepositRecord;
import net.medievalrp.omniscience2.api.event.ContainerWithdrawRecord;
import net.medievalrp.omniscience2.api.event.EventRecord;
import net.medievalrp.omniscience2.api.event.RecordContext;
import net.medievalrp.omniscience2.api.event.StoredItem;
import net.medievalrp.omniscience2.api.extension.EventExtractor;
import net.medievalrp.omniscience2.api.util.BlockLocation;
import net.medievalrp.omniscience2.plugin.listener.ExtractorSupport;
import net.medievalrp.omniscience2.plugin.util.BlockLocations;
import net.medievalrp.omniscience2.plugin.util.ItemSerialization;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.ChiseledBookshelf;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ChiseledBookshelfInventory;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

public final class BookshelfExtractor implements EventExtractor<PlayerInteractEvent, EventRecord> {

    private final ExtractorSupport support;

    public BookshelfExtractor(ExtractorSupport support) {
        this.support = support;
    }

    @Override
    public Class<PlayerInteractEvent> eventType() {
        return PlayerInteractEvent.class;
    }

    @Override
    public Set<String> events() {
        return Set.of("bookshelf-insert", "bookshelf-remove");
    }

    @Override
    public Stream<EventRecord> extract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return Stream.empty();
        }
        if (event.getHand() != EquipmentSlot.HAND) {
            return Stream.empty();
        }
        Block block = event.getClickedBlock();
        if (block == null || block.getType() != Material.CHISELED_BOOKSHELF) {
            return Stream.empty();
        }
        if (!(block.getState(false) instanceof ChiseledBookshelf state)) {
            return Stream.empty();
        }
        if (event.getClickedPosition() == null) {
            return Stream.empty();
        }
        int slot;
        try {
            slot = state.getSlot(event.getClickedPosition());
        } catch (RuntimeException ex) {
            return Stream.empty();
        }
        if (slot < 0) {
            return Stream.empty();
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
            return Stream.of(ContainerWithdrawRecord.of(ctx, "bookshelf-remove", existing.getType().name(),
                    "CHISELED_BOOKSHELF", slot, existing.getAmount(), removed, null));
        }
        if (hasInHand) {
            StoredItem inserted = ItemSerialization.storedItem(slot, inHand);
            RecordContext ctx = support.playerContext(player, location);
            return Stream.of(ContainerDepositRecord.of(ctx, "bookshelf-insert", inHand.getType().name(),
                    "CHISELED_BOOKSHELF", slot, 1, null, inserted));
        }
        return Stream.empty();
    }

    private static boolean isBook(Material material) {
        return material == Material.BOOK
                || material == Material.WRITABLE_BOOK
                || material == Material.WRITTEN_BOOK
                || material == Material.ENCHANTED_BOOK
                || material == Material.KNOWLEDGE_BOOK;
    }
}
