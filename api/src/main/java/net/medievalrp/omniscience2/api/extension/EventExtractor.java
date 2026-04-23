package net.medievalrp.omniscience2.api.extension;

import java.util.stream.Stream;
import net.medievalrp.omniscience2.api.event.EventRecord;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;

public interface EventExtractor<E extends Event, R extends EventRecord> {

    Class<E> eventType();

    default EventPriority priority() {
        return EventPriority.MONITOR;
    }

    default boolean ignoreCancelled() {
        return true;
    }

    Stream<R> extract(E event);
}
