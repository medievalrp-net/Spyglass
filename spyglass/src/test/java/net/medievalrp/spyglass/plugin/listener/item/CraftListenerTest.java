package net.medievalrp.spyglass.plugin.listener.item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.medievalrp.spyglass.api.event.CraftRecord;
import net.medievalrp.spyglass.api.event.EventRecord;
import net.medievalrp.spyglass.api.event.StoredItem;
import net.medievalrp.spyglass.api.util.Duration;
import net.medievalrp.spyglass.plugin.listener.RecordingSupport;
import net.medievalrp.spyglass.plugin.pipeline.AsyncRecorder;
import net.medievalrp.spyglass.plugin.pipeline.Recorder;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link CraftListener}: a player craft emits one {@code craft} record with
 * the crafting player as source, the output material as target, the matrix as
 * ingredients, and a shift-click multiplying the recorded amount. Mirrors the
 * off-main deferral of {@link ItemPickupListenerTest} — the heavy projection
 * runs on the injected executor.
 */
class CraftListenerTest {

    private static final UUID PLAYER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID WORLD_ID = UUID.fromString("77777777-7777-7777-7777-777777777777");

    private final CapturingRecorder recorder = new CapturingRecorder();
    private final RecordingSupport support = new RecordingSupport(Duration.parse("4w"), "test");
    // Strong ref so Location's weak World reference can't be collected mid-test.
    private final World world = mock(World.class);

    @Test
    void playerCraftEmitsCraftRecordWithOutputAndIngredients() {
        List<Runnable> deferred = new ArrayList<>();
        CraftListener listener = new CraftListener(recorder, support, deferred::add);

        CraftItemEvent event = craft(
                mockStack(Material.DIAMOND_PICKAXE, 1),
                false,
                mockStack(Material.DIAMOND, 1), mockStack(Material.DIAMOND, 1),
                mockStack(Material.DIAMOND, 1), mockStack(Material.STICK, 1),
                mockStack(Material.STICK, 1));
        listener.onCraftItem(event);

        // Heavy work deferred: one task queued, nothing recorded inline.
        assertThat(deferred).hasSize(1);
        assertThat(recorder.records).isEmpty();
        deferred.get(0).run();

        assertThat(recorder.records).hasSize(1);
        CraftRecord record = (CraftRecord) recorder.records.get(0);
        assertThat(record.event()).isEqualTo("craft");
        assertThat(record.target()).isEqualTo("DIAMOND_PICKAXE");
        assertThat(record.amount()).isEqualTo(1);
        assertThat(record.source().playerId()).isEqualTo(PLAYER_ID);
        assertThat(record.result()).isNotNull();
        assertThat(record.result().material()).isEqualTo("DIAMOND_PICKAXE");
        assertThat(record.ingredients()).extracting(StoredItem::material)
                .containsExactlyInAnyOrder("DIAMOND", "DIAMOND", "DIAMOND", "STICK", "STICK");
    }

    @Test
    void shiftClickMultipliesAmountByMinimumIngredientStack() {
        List<Runnable> deferred = new ArrayList<>();
        CraftListener listener = new CraftListener(recorder, support, deferred::add);

        // Output 4 torches per craft; matrix has 8 coal and 16 sticks. The
        // bounding stack is 8, so a shift-click crafts 8 sets -> 32 torches.
        CraftItemEvent event = craft(
                mockStack(Material.TORCH, 4),
                true,
                mockStack(Material.COAL, 8), mockStack(Material.STICK, 16));
        listener.onCraftItem(event);
        deferred.get(0).run();

        CraftRecord record = (CraftRecord) recorder.records.get(0);
        assertThat(record.amount()).isEqualTo(32);
    }

    @Test
    void ignoresNonPlayerClicker() {
        List<Runnable> deferred = new ArrayList<>();
        CraftListener listener = new CraftListener(recorder, support, deferred::add);

        CraftItemEvent event = craft(mockStack(Material.DIAMOND_PICKAXE, 1), false,
                mockStack(Material.DIAMOND, 1));
        when(event.getWhoClicked()).thenReturn(mock(org.bukkit.entity.HumanEntity.class));
        listener.onCraftItem(event);

        assertThat(deferred).isEmpty();
        assertThat(recorder.records).isEmpty();
    }

    @Test
    void ignoresAirResult() {
        List<Runnable> deferred = new ArrayList<>();
        CraftListener listener = new CraftListener(recorder, support, deferred::add);

        ItemStack air = mock(ItemStack.class);
        when(air.getType()).thenReturn(Material.AIR);
        CraftItemEvent event = craft(air, false, mockStack(Material.DIAMOND, 1));
        listener.onCraftItem(event);

        assertThat(deferred).isEmpty();
        assertThat(recorder.records).isEmpty();
    }

    // ── fixtures ─────────────────────────────────────────────────

    private CraftItemEvent craft(ItemStack result, boolean shiftClick, ItemStack... matrix) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(PLAYER_ID);
        when(player.getName()).thenReturn("Alice");
        when(player.getLocation()).thenReturn(new Location(world, 1, 64, 2));

        when(world.getUID()).thenReturn(WORLD_ID);
        when(world.getName()).thenReturn("world");

        CraftingInventory inventory = mock(CraftingInventory.class);
        when(inventory.getMatrix()).thenReturn(matrix);
        // 2x2 grid / table without a resolvable block: null location -> the
        // listener falls back to the player's location.
        when(inventory.getLocation()).thenReturn(null);

        CraftItemEvent event = mock(CraftItemEvent.class);
        when(event.getWhoClicked()).thenReturn(player);
        when(event.getCurrentItem()).thenReturn(result);
        when(event.getInventory()).thenReturn(inventory);
        when(event.isShiftClick()).thenReturn(shiftClick);
        return event;
    }

    private static ItemStack mockStack(Material material, int amount) {
        ItemStack stack = mock(ItemStack.class);
        when(stack.getType()).thenReturn(material);
        when(stack.getAmount()).thenReturn(amount);
        when(stack.getItemMeta()).thenReturn(null);
        when(stack.serializeAsBytes()).thenReturn(new byte[]{1, 2, 3});
        ItemStack clone = mock(ItemStack.class);
        when(clone.getType()).thenReturn(material);
        when(clone.getAmount()).thenReturn(amount);
        when(clone.getItemMeta()).thenReturn(null);
        when(clone.serializeAsBytes()).thenReturn(new byte[]{1, 2, 3});
        when(stack.clone()).thenReturn(clone);
        return stack;
    }

    private static final class CapturingRecorder implements Recorder {
        final List<EventRecord> records = new ArrayList<>();

        @Override
        public void record(EventRecord record) {
            records.add(record);
        }

        @Override
        public boolean flush(Duration timeout) {
            return true;
        }

        @Override
        public AsyncRecorder.ShutdownReport shutdown(Duration timeout) {
            return null;
        }
    }
}
