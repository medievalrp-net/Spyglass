package net.medievalrp.spyglass.plugin.listener.item;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import net.medievalrp.spyglass.api.event.CraftRecord;
import net.medievalrp.spyglass.api.event.Origin;
import net.medievalrp.spyglass.api.event.RecordContext;
import net.medievalrp.spyglass.api.event.Source;
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
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;

/**
 * Records a player crafting an item via {@link CraftItemEvent} — a crafting
 * table or the 2x2 inventory grid — as a {@code craft} event.
 *
 * <p>Mirrors {@link ItemPickupListener}'s off-main pattern: the cheap snapshots
 * (result + matrix clones) and the {@link RecordContext} (event time + the
 * time-ordered v7 id) are taken on the handling thread, while the heavier item
 * projections run on the injected serializer. {@link CraftRecord} is not
 * Rollbackable, so deferring has no flush / read-your-writes interaction.
 */
@ApiStatus.Internal
public final class CraftListener implements RecordingListener {

    private final Recorder recorder;
    private final RecordingSupport support;
    private final Executor serializer;

    public CraftListener(Recorder recorder, RecordingSupport support, Executor serializer) {
        this.recorder = recorder;
        this.support = support;
        this.serializer = serializer;
    }

    @Override
    public Set<String> events() {
        return Set.of("craft");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraftItem(CraftItemEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        ItemStack result = event.getCurrentItem();
        if (result == null || result.getType() == Material.AIR) {
            return;
        }
        String target = result.getType().name();
        ItemStack resultSnapshot = result.clone();

        // Snapshot the crafting matrix — one craft's recipe inputs. Each
        // occupied slot becomes one lean ingredient projection; the minimum
        // occupied stack size bounds how many full sets a shift-click yields.
        CraftingInventory inventory = event.getInventory();
        List<ItemStack> matrixSnapshot = new ArrayList<>();
        int minOccupied = Integer.MAX_VALUE;
        for (ItemStack ingredient : inventory.getMatrix()) {
            if (ingredient != null && ingredient.getType() != Material.AIR) {
                matrixSnapshot.add(ingredient.clone());
                minOccupied = Math.min(minOccupied, ingredient.getAmount());
            }
        }
        // CraftItemEvent fires once even for a shift-click that crafts many.
        // Best-effort estimate (see spec): sets * resultAmount, an upper bound
        // when the inventory fills mid-craft. A normal click yields one set.
        int sets = event.isShiftClick() && minOccupied != Integer.MAX_VALUE ? minOccupied : 1;
        int amount = sets * result.getAmount();

        // Crafting-table block when present; the 2x2 inventory grid has no
        // block, so fall back to the player's location.
        BlockLocation location = inventory.getLocation() != null
                ? BlockLocations.fromLocation(inventory.getLocation())
                : BlockLocations.fromLocation(player.getLocation());
        Origin origin = support.playerOrigin();
        Source source = support.playerSource(player);
        RecordContext ctx = support.context(origin, source, location);

        serializer.execute(() -> {
            StoredItem output = ItemSerialization.storedItemProjection(0, resultSnapshot);
            List<StoredItem> ingredients = new ArrayList<>(matrixSnapshot.size());
            for (ItemStack ingredient : matrixSnapshot) {
                StoredItem projection = ItemSerialization.storedItemProjection(0, ingredient);
                if (projection != null) {
                    ingredients.add(projection);
                }
            }
            recorder.record(CraftRecord.of(ctx, target, amount, output, ingredients));
        });
    }
}
