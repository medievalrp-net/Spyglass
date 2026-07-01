package net.medievalrp.spyglass.plugin.imports;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.medievalrp.spyglass.api.event.CustomRecord;
import net.medievalrp.spyglass.api.event.EventRecord;
import net.medievalrp.spyglass.api.event.Origin;
import net.medievalrp.spyglass.api.event.Source;
import net.medievalrp.spyglass.plugin.storage.RecordStore;
import org.junit.jupiter.api.Test;

class RecordStoreSinkTest {

    /** Minimal RecordStore double: records each save() batch, tracks close(). */
    private static final class FakeStore implements RecordStore {
        final List<List<EventRecord>> batches = new ArrayList<>();
        boolean closed;
        @Override public void save(List<EventRecord> records) {
            batches.add(List.copyOf(records));
        }
        @Override public net.medievalrp.spyglass.api.query.QueryResult query(
                net.medievalrp.spyglass.api.query.QueryRequest request) {
            throw new UnsupportedOperationException();
        }
        @Override public void close() { closed = true; }
    }

    @Test
    void flushesInBatchesAndCountsWritten() {
        FakeStore store = new FakeStore();
        RecordStoreSink sink = new RecordStoreSink(store, 2);
        sink.accept(rec()); sink.accept(rec()); // triggers a batch of 2
        sink.accept(rec());                     // one buffered
        assertThat(store.batches).hasSize(1);
        assertThat(store.batches.get(0)).hasSize(2);
        sink.flush();                           // trailing partial batch
        assertThat(store.batches).hasSize(2);
        assertThat(sink.written()).isEqualTo(3);
    }

    @Test
    void closeFlushesButDoesNotCloseTheStore() {
        FakeStore store = new FakeStore();
        RecordStoreSink sink = new RecordStoreSink(store, 10);
        sink.accept(rec());
        sink.close();
        assertThat(store.batches).hasSize(1);   // flushed
        assertThat(store.closed).isFalse();     // store outlives the import
    }

    private static EventRecord rec() {
        // Any concrete EventRecord works; use the mapper's output type in real code.
        Instant now = Instant.now();
        return new CustomRecord(
                UUID.randomUUID(),
                "test-event",
                now,
                now.plusSeconds(86400),
                Origin.player(),
                Source.player(UUID.randomUUID(), "test-player"),
                null,
                "test",
                "test-target",
                null,
                null);
    }
}
