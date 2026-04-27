package net.medievalrp.spyglass.plugin.pipeline;

import net.medievalrp.spyglass.api.event.EventRecord;
import net.medievalrp.spyglass.api.util.Duration;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public interface Recorder {

    void record(EventRecord record);

    AsyncRecorder.ShutdownReport shutdown(Duration timeout);
}
