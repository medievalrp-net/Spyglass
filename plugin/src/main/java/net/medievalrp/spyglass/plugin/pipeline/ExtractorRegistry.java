package net.medievalrp.spyglass.plugin.pipeline;

import java.util.ArrayList;
import java.util.List;
import net.medievalrp.spyglass.api.event.EventRecord;
import net.medievalrp.spyglass.api.extension.EventExtractor;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.EventException;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class ExtractorRegistry {

    private final List<EventExtractor<?, ?>> extractors = new ArrayList<>();
    private final Recorder recorder;

    public ExtractorRegistry(Recorder recorder) {
        this.recorder = recorder;
    }

    public void register(JavaPlugin plugin, EventExtractor<?, ?> extractor) {
        extractors.add(extractor);
        Listener listener = new Listener() {
        };
        Bukkit.getPluginManager().registerEvent(
                extractor.eventType(),
                listener,
                extractor.priority(),
                executor(extractor),
                plugin,
                extractor.ignoreCancelled());
    }

    private EventExecutor executor(EventExtractor<?, ?> extractor) {
        return (ignored, event) -> dispatch(extractor, event);
    }

    @SuppressWarnings("unchecked")
    private <E extends Event, R extends EventRecord> void dispatch(EventExtractor<?, ?> rawExtractor, Event event) throws EventException {
        EventExtractor<E, R> extractor = (EventExtractor<E, R>) rawExtractor;
        extractor.extract((E) event).forEach(recorder::record);
    }
}
