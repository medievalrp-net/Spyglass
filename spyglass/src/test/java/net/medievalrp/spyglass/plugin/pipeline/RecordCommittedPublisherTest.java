package net.medievalrp.spyglass.plugin.pipeline;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.medievalrp.spyglass.api.event.EventRecord;
import net.medievalrp.spyglass.api.event.JoinRecord;
import net.medievalrp.spyglass.api.event.Origin;
import net.medievalrp.spyglass.api.event.Source;
import net.medievalrp.spyglass.api.util.BlockLocation;
import org.junit.jupiter.api.Test;

/**
 * Pins the committed-hook guard (#96): {@code RecordCommittedEvent} must
 * not be allocated or dispatched when nothing is listening, must fire
 * unchanged when a listener is present, and must re-read the registration
 * state per record (no stale caching). The injected {@code dispatch}
 * stands in for {@code Bukkit.getPluginManager().callEvent(...)} so the
 * guard is exercised headless, without a running server.
 */
class RecordCommittedPublisherTest {

    private static EventRecord sampleRecord() {
        Instant now = Instant.now();
        return new JoinRecord(
                UUID.randomUUID(), "join", now, now.plusSeconds(60),
                Origin.player(), Source.player(UUID.randomUUID(), "tester"),
                new BlockLocation(UUID.randomUUID(), "world", 0, 64, 0),
                "test", "tester", "127.0.0.1");
    }

    @Test
    void skipsDispatchWhenNoListenerIsRegistered() {
        @SuppressWarnings("unchecked")
        Consumer<EventRecord> dispatch = mock(Consumer.class);
        RecordCommittedPublisher publisher = new RecordCommittedPublisher(() -> 0, dispatch);

        publisher.accept(sampleRecord());

        verifyNoInteractions(dispatch);
    }

    @Test
    void dispatchesWhenAListenerIsRegistered() {
        @SuppressWarnings("unchecked")
        Consumer<EventRecord> dispatch = mock(Consumer.class);
        RecordCommittedPublisher publisher = new RecordCommittedPublisher(() -> 1, dispatch);
        EventRecord record = sampleRecord();

        publisher.accept(record);

        verify(dispatch).accept(record);
    }

    @Test
    void reflectsListenerCountOnEachCallWithoutCaching() {
        // The registration state can change at runtime; the guard must
        // re-read it per record, never cache the first answer.
        AtomicInteger registered = new AtomicInteger(0);
        @SuppressWarnings("unchecked")
        Consumer<EventRecord> dispatch = mock(Consumer.class);
        RecordCommittedPublisher publisher =
                new RecordCommittedPublisher(registered::get, dispatch);

        publisher.accept(sampleRecord());   // none registered -> skipped
        verifyNoInteractions(dispatch);

        registered.set(1);
        EventRecord second = sampleRecord();
        publisher.accept(second);           // now registered -> dispatched
        verify(dispatch).accept(second);
    }
}
