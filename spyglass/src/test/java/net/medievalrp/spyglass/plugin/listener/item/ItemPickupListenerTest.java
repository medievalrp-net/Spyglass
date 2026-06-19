package net.medievalrp.spyglass.plugin.listener.item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.medievalrp.spyglass.api.event.EventRecord;
import net.medievalrp.spyglass.api.event.ItemPickupRecord;
import net.medievalrp.spyglass.api.util.Duration;
import net.medievalrp.spyglass.plugin.listener.RecordingSupport;
import net.medievalrp.spyglass.plugin.pipeline.Recorder;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

/**
 * Pins the off-main pickup serialization (#97): the heavy item
 * serialization is handed to the injected executor, while the cheap
 * snapshot (clone) and the context (occurred + the time-ordered v7 id)
 * are taken on the handling (main) thread so the record reflects event
 * time, not serialization time. {@code ItemPickupRecord} is not
 * Rollbackable, so this deferral has no flush / read-your-writes
 * interaction.
 */
class ItemPickupListenerTest {

    private static final UUID PLAYER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID WORLD_ID = UUID.fromString("77777777-7777-7777-7777-777777777777");

    private final CapturingRecorder recorder = new CapturingRecorder();
    private final RecordingSupport support = new RecordingSupport(Duration.parse("4w"), "test");
    // Strong ref so Location's weak World reference can't be collected mid-test.
    private final World world = mock(World.class);

    @Test
    void serializesAndRecordsOnTheExecutorNotInline() {
        List<Runnable> deferred = new ArrayList<>();
        ItemPickupListener listener = new ItemPickupListener(recorder, support, deferred::add);

        listener.onEntityPickupItem(playerPickup(mockStack(Material.IRON_INGOT, 5)));

        // Heavy work deferred: one task queued, nothing recorded inline.
        assertThat(deferred).hasSize(1);
        assertThat(recorder.records).isEmpty();

        deferred.get(0).run();

        assertThat(recorder.records).hasSize(1);
        ItemPickupRecord record = (ItemPickupRecord) recorder.records.get(0);
        assertThat(record.target()).isEqualTo("IRON_INGOT");
        assertThat(record.amount()).isEqualTo(5);
        assertThat(record.item()).isNotNull();
        assertThat(record.item().material()).isEqualTo("IRON_INGOT");
    }

    @Test
    void stampsOccurredAndIdOnHandlingThreadNotAtSerializationTime() {
        List<Runnable> deferred = new ArrayList<>();
        ItemPickupListener listener = new ItemPickupListener(recorder, support, deferred::add);

        Instant before = Instant.now();
        listener.onEntityPickupItem(playerPickup(mockStack(Material.IRON_INGOT, 1)));
        Instant after = Instant.now();

        // The record is only built when the deferred task runs, strictly
        // after the handling window — yet occurred must fall inside it.
        deferred.get(0).run();

        ItemPickupRecord record = (ItemPickupRecord) recorder.records.get(0);
        assertThat(record.occurred()).isBetween(before, after);
        assertThat(record.id()).isNotNull();
    }

    @Test
    void clonesStackOnHandlingThreadBeforeDeferring() {
        ItemStack live = mockStack(Material.DIAMOND, 2);
        List<Runnable> deferred = new ArrayList<>();
        ItemPickupListener listener = new ItemPickupListener(recorder, support, deferred::add);

        listener.onEntityPickupItem(playerPickup(live));

        // The snapshot must be taken synchronously, before the deferred
        // task could observe a mutated/consumed live stack.
        verify(live).clone();
        assertThat(recorder.records).isEmpty();
    }

    @Test
    void ignoresAirWithoutCloningOrDeferring() {
        ItemStack air = mock(ItemStack.class);
        when(air.getType()).thenReturn(Material.AIR);
        List<Runnable> deferred = new ArrayList<>();
        ItemPickupListener listener = new ItemPickupListener(recorder, support, deferred::add);

        listener.onEntityPickupItem(playerPickup(air));

        assertThat(deferred).isEmpty();
        assertThat(recorder.records).isEmpty();
        verify(air, never()).clone();
    }

    // ── fixtures ─────────────────────────────────────────────────

    private EntityPickupItemEvent playerPickup(ItemStack stack) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(PLAYER_ID);
        when(player.getName()).thenReturn("Alice");

        when(world.getUID()).thenReturn(WORLD_ID);
        when(world.getName()).thenReturn("world");
        Item itemEntity = mock(Item.class);
        when(itemEntity.getItemStack()).thenReturn(stack);
        when(itemEntity.getLocation()).thenReturn(new Location(world, 10, 64, 20));

        EntityPickupItemEvent event = mock(EntityPickupItemEvent.class);
        when(event.getItem()).thenReturn(itemEntity);
        when(event.getEntity()).thenReturn(player);
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
        public net.medievalrp.spyglass.plugin.pipeline.AsyncRecorder.ShutdownReport shutdown(Duration timeout) {
            return null;
        }
    }
}
