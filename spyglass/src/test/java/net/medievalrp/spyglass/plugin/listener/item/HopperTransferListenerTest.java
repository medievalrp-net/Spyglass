package net.medievalrp.spyglass.plugin.listener.item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.medievalrp.spyglass.api.event.EventRecord;
import net.medievalrp.spyglass.api.event.ItemDropRecord;
import net.medievalrp.spyglass.api.event.ItemPickupRecord;
import net.medievalrp.spyglass.api.util.Duration;
import net.medievalrp.spyglass.plugin.listener.RecordingSupport;
import net.medievalrp.spyglass.plugin.pipeline.Recorder;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

/**
 * The automated hopper/dropper transfer listener (#226). Pins the two
 * invariants that matter: the source and destination are both logged
 * (transfer-out / transfer-in) with no work done inline on the server thread
 * (everything runs on the injected serializer), and a sustained flow is
 * collapsed by the dedup instead of flooding the store.
 */
class HopperTransferListenerTest {

    private static final UUID WORLD_ID = UUID.fromString("77777777-7777-7777-7777-777777777777");

    private final CapturingRecorder recorder = new CapturingRecorder();
    private final RecordingSupport support = new RecordingSupport(Duration.parse("4w"), "test");
    // Strong ref so Location's weak World reference can't be collected mid-test.
    private final World world = mock(World.class);

    @Test
    void logsBothEndpointsOnTheExecutorNotInline() {
        List<Runnable> deferred = new ArrayList<>();
        HopperTransferListener listener = listener(deferred, enabled("transfer-in", "transfer-out"),
                new TransferDedup(60_000L, 1000));

        listener.onInventoryMoveItem(move(mockStack(Material.COBBLESTONE, 3)));

        // Nothing recorded inline: one deferred task, no records yet.
        assertThat(deferred).hasSize(1);
        assertThat(recorder.records).isEmpty();

        deferred.get(0).run();

        assertThat(recorder.records).hasSize(2);
        ItemDropRecord out = (ItemDropRecord) find("transfer-out");
        ItemPickupRecord in = (ItemPickupRecord) find("transfer-in");
        // transfer-out sits on the source block, transfer-in on the destination.
        assertThat(out.location().y()).isEqualTo(64);
        assertThat(in.location().y()).isEqualTo(63);
        assertThat(out.target()).isEqualTo("COBBLESTONE");
        assertThat(in.target()).isEqualTo("COBBLESTONE");
        assertThat(out.amount()).isEqualTo(3);
        assertThat(in.amount()).isEqualTo(3);
        assertThat(in.item().material()).isEqualTo("COBBLESTONE");
    }

    @Test
    void collapsesRepeatedMovesToASinglePair() {
        List<Runnable> deferred = new ArrayList<>();
        HopperTransferListener listener = listener(deferred, enabled("transfer-in", "transfer-out"),
                new TransferDedup(60_000L, 1000));

        // A hopper line firing the identical move five times in one window.
        for (int i = 0; i < 5; i++) {
            listener.onInventoryMoveItem(move(mockStack(Material.COBBLESTONE, 1)));
        }
        assertThat(deferred).hasSize(5);
        deferred.forEach(Runnable::run);

        // Only the first move logs; the rest are deduped away.
        assertThat(recorder.records).hasSize(2);
    }

    @Test
    void honoursPerEventToggle() {
        List<Runnable> deferred = new ArrayList<>();
        HopperTransferListener listener = listener(deferred, enabled("transfer-out"),
                new TransferDedup(60_000L, 1000));

        listener.onInventoryMoveItem(move(mockStack(Material.COBBLESTONE, 1)));
        deferred.get(0).run();

        assertThat(recorder.records).hasSize(1);
        assertThat(recorder.records.get(0).event()).isEqualTo("transfer-out");
    }

    @Test
    void bothDisabledDoesNothingAndDefersNothing() {
        List<Runnable> deferred = new ArrayList<>();
        HopperTransferListener listener = listener(deferred, enabled(), new TransferDedup(60_000L, 1000));

        listener.onInventoryMoveItem(move(mockStack(Material.COBBLESTONE, 1)));

        assertThat(deferred).isEmpty();
        assertThat(recorder.records).isEmpty();
    }

    @Test
    void ignoresAirWithoutCloningOrDeferring() {
        ItemStack air = mock(ItemStack.class);
        when(air.getType()).thenReturn(Material.AIR);
        List<Runnable> deferred = new ArrayList<>();
        HopperTransferListener listener = listener(deferred, enabled("transfer-in", "transfer-out"),
                new TransferDedup(60_000L, 1000));

        listener.onInventoryMoveItem(move(air));

        assertThat(deferred).isEmpty();
        assertThat(recorder.records).isEmpty();
        verify(air, never()).clone();
    }

    @Test
    void skipsVirtualInventoryWithNoLocation() {
        List<Runnable> deferred = new ArrayList<>();
        HopperTransferListener listener = listener(deferred, enabled("transfer-in", "transfer-out"),
                new TransferDedup(60_000L, 1000));

        InventoryMoveItemEvent event = move(mockStack(Material.COBBLESTONE, 1));
        when(event.getSource().getLocation()).thenReturn(null);  // a virtual (blockless) inventory

        listener.onInventoryMoveItem(event);

        assertThat(deferred).isEmpty();
        assertThat(recorder.records).isEmpty();
    }

    // ── fixtures ─────────────────────────────────────────────────

    private HopperTransferListener listener(List<Runnable> deferred, Set<String> enabled, TransferDedup dedup) {
        return new HopperTransferListener(recorder, support, deferred::add, enabled, dedup);
    }

    private static Set<String> enabled(String... names) {
        return new HashSet<>(List.of(names));
    }

    private EventRecord find(String event) {
        return recorder.records.stream()
                .filter(r -> r.event().equals(event))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no record for event " + event));
    }

    private InventoryMoveItemEvent move(ItemStack stack) {
        when(world.getUID()).thenReturn(WORLD_ID);
        when(world.getName()).thenReturn("world");

        Inventory source = mock(Inventory.class);
        when(source.getLocation()).thenReturn(new Location(world, 10, 64, 20));
        Inventory destination = mock(Inventory.class);
        when(destination.getLocation()).thenReturn(new Location(world, 10, 63, 20));
        // getType() is left unstubbed (defaults to null): referencing a real
        // InventoryType constant here would hit the registry, which is not
        // bootstrapped in a unit test. The listener null-guards it to "hopper".
        Inventory initiator = mock(Inventory.class);

        InventoryMoveItemEvent event = mock(InventoryMoveItemEvent.class);
        when(event.getItem()).thenReturn(stack);
        when(event.getSource()).thenReturn(source);
        when(event.getDestination()).thenReturn(destination);
        when(event.getInitiator()).thenReturn(initiator);
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
