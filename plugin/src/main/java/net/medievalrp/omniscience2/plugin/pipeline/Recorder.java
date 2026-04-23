package net.medievalrp.omniscience2.plugin.pipeline;

import net.medievalrp.omniscience2.api.event.EventRecord;
import net.medievalrp.omniscience2.api.util.Duration;

public interface Recorder {

    void record(EventRecord record);

    AsyncRecorder.ShutdownReport shutdown(Duration timeout);
}
