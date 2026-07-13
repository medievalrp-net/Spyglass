package net.medievalrp.spyglass.plugin.listener.modern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import net.medievalrp.spyglass.api.event.ContainerWithdrawRecord;
import net.medievalrp.spyglass.api.event.EventRecord;
import net.medievalrp.spyglass.api.util.Duration;
import net.medievalrp.spyglass.plugin.listener.RecordingSupport;
import net.medievalrp.spyglass.plugin.pipeline.Recorder;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.block.CrafterCraftEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

/**
 * Pins the deferred serialization strategy for CrafterListener (#327):
 *
 * <ul>
 *   <li>The handler takes only a cheap snapshot on the handling thread;
 *       serializeAsBytes() and record() run on the injected serializer.</li>
 *   <li>The finished record is shape-identical to the old inline path
 *       (crafter withdraw, slot 0, full item blob, null after-item) - the
 *       record is rollbackable, so the blob must survive the deferral.</li>
 *   <li>The RecordContext (occurred + id) is stamped at event time on the
 *       handling thread, not at serialization time (#98 read-your-writes).</li>
 * </ul>
 */
class CrafterListenerTest {

    private static final UUID WORLD_ID = UUID.fromString("77777777-7777-7777-7777-777777777777");

    private final CapturingRecorder recorder = new CapturingRecorder();
    private final RecordingSupport support = new RecordingSupport(Duration.parse("4w"), "test");
    // Strong ref so Location's weak World reference can't be collected mid-test.
    private final World world = mock(World.class);

    @Test
    void serializationAndRecordAreDeferredToTheExecutorNotInline() {
        List<Runnable> deferred = new ArrayList<>();
        CrafterListener listener = new CrafterListener(recorder, support, deferred::add);
        ItemStack result = mockStack(Material.COBBLESTONE, 4);

        listener.onCrafterCraft(craft(result));

        // Nothing recorded inline: one deferred task, no serialization yet.
        assertThat(deferred).hasSize(1);
        assertThat(recorder.records).isEmpty();
        verify(result, never()).serializeAsBytes();

        deferred.get(0).run();

        assertThat(recorder.records).hasSize(1);
        verify(result).serializeAsBytes();
    }

    /**
     * The record the deferred task builds must match the old inline path
     * field for field, including the full base64 blob - crafter records are
     * rollbackable ContainerWithdrawRecords, so the blob is the replay
     * source of truth and must not degrade to a projection.
     */
    @Test
    void finishedRecordMatchesTheInlineShape() {
        List<Runnable> deferred = new ArrayList<>();
        CrafterListener listener = new CrafterListener(recorder, support, deferred::add);

        listener.onCrafterCraft(craft(mockStack(Material.COBBLESTONE, 4)));
        deferred.get(0).run();

        ContainerWithdrawRecord record = (ContainerWithdrawRecord) recorder.records.get(0);
        assertThat(record.event()).isEqualTo("crafter");
        assertThat(record.target()).isEqualTo("COBBLESTONE");
        assertThat(record.containerType()).isEqualTo("CRAFTER");
        assertThat(record.slot()).isEqualTo(0);
        assertThat(record.amount()).isEqualTo(4);
        assertThat(record.beforeItem().material()).isEqualTo("COBBLESTONE");
        assertThat(record.beforeItem().data())
                .isEqualTo(Base64.getEncoder().encodeToString(new byte[]{1, 2, 3}));
        assertThat(record.afterItem()).isNull();
        assertThat(record.location().y()).isEqualTo(64);
    }

    /**
     * #98 read-your-writes: occurred and id must be stamped at event time on
     * the handling thread, not at serialization time on the executor thread.
     */
    @Test
    void occurredAndIdAreStampedAtEventTimeNotAtSerializationTime() {
        List<Runnable> deferred = new ArrayList<>();
        CrafterListener listener = new CrafterListener(recorder, support, deferred::add);

        Instant before = Instant.now();
        listener.onCrafterCraft(craft(mockStack(Material.COBBLESTONE, 1)));
        Instant after = Instant.now();

        // Simulate delayed executor (the record is built strictly after the event window).
        deferred.get(0).run();

        ContainerWithdrawRecord record = (ContainerWithdrawRecord) recorder.records.get(0);
        assertThat(record.occurred())
                .as("occurred must be stamped at event time, not serialization time")
                .isBetween(before, after);
        assertThat(record.id()).isNotNull();
    }

    @Test
    void nullResultDefersNothing() {
        List<Runnable> deferred = new ArrayList<>();
        CrafterListener listener = new CrafterListener(recorder, support, deferred::add);
        CrafterCraftEvent event = mock(CrafterCraftEvent.class);
        when(event.getResult()).thenReturn(null);

        listener.onCrafterCraft(event);

        assertThat(deferred).isEmpty();
        assertThat(recorder.records).isEmpty();
    }

    // ── fixtures ────────────────────────────────────────────────

    private CrafterCraftEvent craft(ItemStack result) {
        when(world.getUID()).thenReturn(WORLD_ID);
        when(world.getName()).thenReturn("world");

        Block block = mock(Block.class);
        when(block.getLocation()).thenReturn(new Location(world, 10, 64, 20));

        CrafterCraftEvent event = mock(CrafterCraftEvent.class);
        when(event.getBlock()).thenReturn(block);
        when(event.getResult()).thenReturn(result);
        return event;
    }

    private static ItemStack mockStack(Material material, int amount) {
        ItemStack stack = mock(ItemStack.class);
        when(stack.getType()).thenReturn(material);
        when(stack.getAmount()).thenReturn(amount);
        when(stack.getItemMeta()).thenReturn(null);
        when(stack.serializeAsBytes()).thenReturn(new byte[]{1, 2, 3});
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
