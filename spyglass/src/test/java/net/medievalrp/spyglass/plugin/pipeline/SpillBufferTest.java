package net.medievalrp.spyglass.plugin.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import net.medievalrp.spyglass.api.event.EventRecord;
import net.medievalrp.spyglass.api.event.JoinRecord;
import net.medievalrp.spyglass.api.event.Origin;
import net.medievalrp.spyglass.api.event.Source;
import net.medievalrp.spyglass.api.util.BlockLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SpillBufferTest {

    private static final Logger LOG = Logger.getLogger("spill-test");

    private static EventRecord record(UUID id) {
        Instant now = Instant.now();
        return new JoinRecord(id, "join", now, now.plusSeconds(60),
                Origin.player(), Source.player(UUID.randomUUID(), "tester"),
                new BlockLocation(UUID.randomUUID(), "world", 0, 64, 0),
                "test", "tester", "127.0.0.1");
    }

    private static List<EventRecord> batch(UUID... ids) {
        List<EventRecord> out = new ArrayList<>();
        for (UUID id : ids) {
            out.add(record(id));
        }
        return out;
    }

    @Test
    void spillThenPollRoundTripsRecordsAndAckClears(@TempDir Path dir) throws Exception {
        SpillBuffer spill = new SpillBuffer(dir, true, LOG);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        spill.spill(batch(a, b));

        assertThat(spill.hasPending()).isTrue();
        assertThat(spill.pendingRecordCount()).isEqualTo(2);

        SpillBuffer.Spilled polled = spill.poll();
        assertThat(polled).isNotNull();
        assertThat(polled.records().stream().map(EventRecord::id).toList())
                .as("spilled records round-trip through the on-disk codec by id")
                .containsExactly(a, b);

        spill.ack(polled);
        assertThat(spill.hasPending()).isFalse();
        assertThat(spill.pendingRecordCount()).isZero();
        assertThat(spill.poll()).isNull();
    }

    @Test
    void pollReturnsOldestSegmentFirst(@TempDir Path dir) throws Exception {
        SpillBuffer spill = new SpillBuffer(dir, true, LOG);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        spill.spill(batch(first));
        spill.spill(batch(second));

        SpillBuffer.Spilled a = spill.poll();
        assertThat(a.records().getFirst().id()).isEqualTo(first);
        spill.ack(a);
        SpillBuffer.Spilled b = spill.poll();
        assertThat(b.records().getFirst().id()).isEqualTo(second);
    }

    @Test
    void drainsManySegmentsInOrderIncludingOnesSpilledMidDrain(@TempDir Path dir) throws Exception {
        // #210: the drain-thread segment cache must replay every segment
        // oldest-first across an empty-cache refill, and pick up spills that
        // land mid-drain (higher seq -> sorted after the cached ones).
        SpillBuffer spill = new SpillBuffer(dir, true, LOG);
        List<UUID> expected = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            UUID id = UUID.randomUUID();
            expected.add(id);
            spill.spill(batch(id));
        }

        List<UUID> drained = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            SpillBuffer.Spilled s = spill.poll();
            drained.add(s.records().getFirst().id());
            spill.ack(s);
        }
        // Two more spills after the cache was already populated + partly drained.
        for (int i = 0; i < 2; i++) {
            UUID id = UUID.randomUUID();
            expected.add(id);
            spill.spill(batch(id));
        }
        SpillBuffer.Spilled s;
        while ((s = spill.poll()) != null) {
            drained.add(s.records().getFirst().id());
            spill.ack(s);
        }

        assertThat(drained)
                .as("every segment replayed once, oldest-first, including mid-drain spills")
                .containsExactlyElementsOf(expected);
        assertThat(spill.hasPending()).isFalse();
        assertThat(spill.pendingRecordCount()).isZero();
    }

    @Test
    void recoversSegmentsLeftByAPriorRun(@TempDir Path dir) throws Exception {
        UUID id = UUID.randomUUID();
        new SpillBuffer(dir, true, LOG).spill(batch(id, UUID.randomUUID()));

        // A fresh instance over the same data folder (i.e. a restart) must see
        // the leftover segment and hand it back to the drain.
        SpillBuffer restarted = new SpillBuffer(dir, true, LOG);
        assertThat(restarted.hasPending()).isTrue();
        assertThat(restarted.pendingRecordCount()).isEqualTo(2);
        assertThat(restarted.poll().records().getFirst().id()).isEqualTo(id);
    }

    @Test
    void disabledSpillIsANoOp(@TempDir Path dir) throws Exception {
        SpillBuffer spill = new SpillBuffer(dir, false, LOG);
        assertThat(spill.enabled()).isFalse();
        spill.spill(batch(UUID.randomUUID()));
        assertThat(spill.hasPending()).isFalse();
        assertThat(spill.poll()).isNull();
    }

    @Test
    void unreadableSegmentIsDroppedNotWedged(@TempDir Path dir) throws Exception {
        // Hand-write a segment whose name parses (seq 0, 5 records) but whose
        // bytes are garbage, then let a fresh buffer seed from it.
        Path spillDir = dir.resolve("spill");
        Files.createDirectories(spillDir);
        Files.write(spillDir.resolve(String.format("%016d.%d.spill", 0, 5)),
                new byte[]{1, 2, 3, 4});

        SpillBuffer spill = new SpillBuffer(dir, true, LOG);
        assertThat(spill.hasPending()).isTrue();
        // poll() must drop the corrupt segment and return null, not throw or
        // loop forever — a wedged segment would otherwise stall the drain.
        assertThat(spill.poll()).isNull();
        assertThat(spill.hasPending()).isFalse();
        assertThat(spill.pendingRecordCount()).isZero();
    }
}
