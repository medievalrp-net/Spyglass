package net.medievalrp.spyglass.plugin.listener.item;

import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import com.destroystokyo.paper.event.server.ServerTickStartEvent;
import java.util.*;
import net.medievalrp.spyglass.api.capture.ItemSerialization;
import net.medievalrp.spyglass.api.event.*;
import net.medievalrp.spyglass.api.util.BlockLocation;
import net.medievalrp.spyglass.plugin.listener.*;
import net.medievalrp.spyglass.plugin.pipeline.Recorder;
import net.medievalrp.spyglass.plugin.util.BlockLocations;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.Chest;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/** Observes only validated, nearby targets and correlates completed slot changes with the golem's hand. */
public final class CopperGolemTransferListener implements RecordingListener {
    private final Recorder recorder;
    private final RecordingSupport support;
    private final Map<UUID, Watch> watches = new HashMap<>();
    private final Map<UUID, Frame> frames = new HashMap<>();
    private final Set<BlockLocation> contaminated = new HashSet<>();

    public CopperGolemTransferListener(Recorder recorder, RecordingSupport support) {
        this.recorder = recorder; this.support = support;
    }
    @Override public Set<String> events() { return Set.of("transfer-withdraw", "transfer-deposit", "transfer-uncertain"); }
    @Override public void register(Plugin plugin) {
        watches.clear(); frames.clear(); contaminated.clear();
        RecordingListener.super.register(plugin);
        OptionalPaperEvents.register(plugin, this,
                "io.papermc.paper.event.entity.ItemTransportingEntityValidateTargetEvent", event -> {
                    Object entity = OptionalPaperEvents.call(event, "getEntity");
                    if (!(entity instanceof LivingEntity golem) || !golem.getType().name().equals("COPPER_GOLEM")
                            || !Boolean.TRUE.equals(OptionalPaperEvents.call(event, "isAllowed"))) return;
                    Block block = (Block) OptionalPaperEvents.call(event, "getBlock");
                    Watch watch = watches.computeIfAbsent(golem.getUniqueId(), id -> new Watch(golem));
                    // A search can inspect many candidates. Retain a bounded, recent set, not entire worlds.
                    if (watch.targets.size() >= 64) watch.targets.remove(watch.targets.keySet().iterator().next());
                    watch.targets.put(BlockLocations.fromBlock(block), block);
                    capture(watch);
                });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onStart(ServerTickStartEvent event) {
        frames.clear(); contaminated.clear();
        watches.values().removeIf(w -> !w.golem.isValid());
        watches.values().forEach(this::capture);
    }
    private void capture(Watch watch) {
        if (frames.containsKey(watch.golem.getUniqueId())) return;
        List<Endpoint> endpoints = new ArrayList<>();
        Set<BlockLocation> seen = new HashSet<>();
        for (Block block : watch.targets.values()) {
            if (!block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4)
                    || !block.getWorld().equals(watch.golem.getWorld())
                    || block.getLocation().distanceSquared(watch.golem.getLocation()) > 16) continue;
            if (!(block.getState() instanceof Container container)) continue;
            Inventory inventory = container.getInventory();
            BlockLocation key = BlockLocations.fromLocation(inventory.getLocation());
            if (seen.add(key)) endpoints.add(new Endpoint(inventory, copy(inventory)));
        }
        if (!endpoints.isEmpty()) frames.put(watch.golem.getUniqueId(),
                new Frame(watch.golem, cloneItem(watch.golem.getEquipment().getItemInMainHand()), endpoints));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEnd(ServerTickEndEvent event) {
        for (Frame frame : frames.values()) {
            if (!frame.golem.isValid()) continue;
            ItemStack hand = cloneItem(frame.golem.getEquipment().getItemInMainHand());
            if (Objects.equals(frame.hand, hand)) continue;
            ItemStack moved = empty(hand) ? frame.hand : hand;
            if (!empty(frame.hand) && !empty(hand) && !frame.hand.isSimilar(hand)) continue;
            int handDelta = count(hand) - count(frame.hand);
            if (handDelta == 0) continue;
            List<Endpoint> matches = new ArrayList<>();
            Map<Endpoint, ItemStack[]> afters = new HashMap<>();
            for (Endpoint endpoint : frame.endpoints) {
                ItemStack[] after = copy(endpoint.inventory);
                afters.put(endpoint, after);
                if (matches(endpoint.before, after, moved, -handDelta)) matches.add(endpoint);
            }
            if (matches.size() == 1 && !tainted(matches.getFirst().inventory)) {
                Endpoint endpoint = matches.getFirst();
                emit(frame.golem, endpoint, afters.get(endpoint));
            } else {
                for (Endpoint endpoint : frame.endpoints) {
                    if (Arrays.equals(endpoint.before, afters.get(endpoint))) continue;
                    for (int slot = 0; slot < endpoint.before.length; slot++) {
                        if (Objects.equals(endpoint.before[slot], afters.get(endpoint)[slot])) continue;
                        var at = slotTarget(endpoint.inventory, slot);
                        var ctx = support.context(support.environmentOrigin("copper-golem-transfer"),
                                support.entitySource(frame.golem.getUniqueId(), "copper_golem"), at.location);
                        recorder.record(CustomRecord.of(ctx, "transfer-uncertain", at.type,
                                "concurrent or ambiguous copper-golem transfer", Map.of("slot", "" + at.slot)));
                    }
                }
            }
        }
        frames.clear();
    }

    /** Match every changed stack including metadata; never manufacture a slot from only an item count. */
    static boolean matches(ItemStack[] before, ItemStack[] after, ItemStack moved, int expected) {
        if (before.length != after.length || empty(moved)) return false;
        int total = 0;
        for (int i = 0; i < before.length; i++) {
            if (Objects.equals(before[i], after[i])) continue;
            if ((!empty(before[i]) && !before[i].isSimilar(moved))
                    || (!empty(after[i]) && !after[i].isSimilar(moved))) return false;
            int delta = count(after[i]) - count(before[i]);
            if (Integer.signum(delta) != Integer.signum(expected)) return false;
            total += delta;
        }
        return total == expected;
    }

    private void emit(LivingEntity golem, Endpoint endpoint, ItemStack[] after) {
        for (int slot = 0; slot < after.length; slot++) {
            int delta = count(after[slot]) - count(endpoint.before[slot]);
            if (delta == 0) continue;
            var target = slotTarget(endpoint.inventory, slot);
            var ctx = support.context(support.environmentOrigin("copper-golem-transfer"),
                    support.entitySource(golem.getUniqueId(), "copper_golem"), target.location);
            var beforeItem = ItemSerialization.storedItem(target.slot, endpoint.before[slot]);
            var afterItem = ItemSerialization.storedItem(target.slot, after[slot]);
            String material = (delta > 0 ? after[slot] : endpoint.before[slot]).getType().name();
            recorder.record(delta > 0
                    ? ContainerDepositRecord.of(ctx, "transfer-deposit", material, target.type, target.slot, delta, beforeItem, afterItem)
                    : ContainerWithdrawRecord.of(ctx, "transfer-withdraw", material, target.type, target.slot, -delta, beforeItem, afterItem));
        }
    }
    private static Target slotTarget(Inventory inventory, int slot) {
        if (inventory.getHolder() instanceof DoubleChest chest) {
            Chest left = (Chest) chest.getLeftSide();
            Chest right = (Chest) chest.getRightSide();
            int size = left.getBlockInventory().getSize();
            Chest half = slot < size ? left : right;
            return new Target(BlockLocations.fromBlock(half.getBlock()), half.getType().name(), slot < size ? slot : slot - size);
        }
        Container container = (Container) inventory.getHolder();
        return new Target(BlockLocations.fromBlock(container.getBlock()), container.getType().name(), slot);
    }
    private void taint(Inventory inventory) {
        if (inventory.getLocation() != null) contaminated.add(BlockLocations.fromLocation(inventory.getLocation()));
    }
    private boolean tainted(Inventory inventory) {
        return inventory.getLocation() == null || contaminated.contains(BlockLocations.fromLocation(inventory.getLocation()));
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) { taint(event.getInventory()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) { taint(event.getInventory()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(InventoryMoveItemEvent event) { taint(event.getSource()); taint(event.getDestination()); }
    private static ItemStack[] copy(Inventory inventory) {
        ItemStack[] out = inventory.getContents();
        for (int i = 0; i < out.length; i++) out[i] = cloneItem(out[i]);
        return out;
    }
    private static ItemStack cloneItem(ItemStack item) { return empty(item) ? null : item.clone(); }
    private static boolean empty(ItemStack item) { return item == null || item.getType() == org.bukkit.Material.AIR; }
    private static int count(ItemStack item) { return empty(item) ? 0 : item.getAmount(); }
    private record Target(BlockLocation location, String type, int slot) {}
    private record Endpoint(Inventory inventory, ItemStack[] before) {}
    private record Frame(LivingEntity golem, ItemStack hand, List<Endpoint> endpoints) {}
    private static class Watch {
        final LivingEntity golem;
        final LinkedHashMap<BlockLocation, Block> targets = new LinkedHashMap<>();
        Watch(LivingEntity golem) { this.golem = golem; }
    }
}
