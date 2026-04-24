package net.medievalrp.omniscience2.api.extension;

import java.util.Set;
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

    /**
     * Event names this extractor emits. The plugin registers the extractor with
     * Bukkit only when at least one of these names is enabled in config. The
     * default is the empty set — an extractor that returns the default is
     * always registered. Override with the concrete names to get config-gating.
     */
    default Set<String> events() {
        return Set.of();
    }
}
