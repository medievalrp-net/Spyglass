package net.medievalrp.spyglass.plugin.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import net.medievalrp.spyglass.api.event.CustomRecord;
import net.medievalrp.spyglass.api.event.EventCatalog;
import net.medievalrp.spyglass.plugin.pipeline.Recorder;
import net.medievalrp.spyglass.plugin.storage.RecordStore;
import org.junit.jupiter.api.Test;

class SpyglassApiImplTest {

    private SpyglassApiImpl api(Set<String> enabled) {
        return new SpyglassApiImpl(
                mock(Recorder.class), mock(RecordStore.class), Runnable::run,
                enabled, null, "srv", Logger.getLogger("test"));
    }

    @Test
    void registerEventMakesItKnownEnabledAndDecodable() {
        Set<String> enabled = ConcurrentHashMap.newKeySet();
        SpyglassApiImpl api = api(enabled);

        assertThat(api.isEventRegistered("apitest-voice")).isFalse();

        api.registerEvent("apitest-voice", "spoke");

        assertThat(api.isEventRegistered("apitest-voice")).isTrue();
        // Added to the live enabled set so EventParam (sharing it) parses a:.
        assertThat(enabled).contains("apitest-voice");
        // Resolves on the read path as a CustomRecord.
        assertThat(EventCatalog.recordClassOf("apitest-voice")).isEqualTo(CustomRecord.class);
        assertThat(EventCatalog.pastTenseOf("apitest-voice")).isEqualTo("spoke");
    }

    @Test
    void builtinEventsReportAsRegistered() {
        SpyglassApiImpl api = api(ConcurrentHashMap.newKeySet());
        assertThat(api.isEventRegistered("say")).isTrue();
        assertThat(api.isEventRegistered("not-a-real-event")).isFalse();
    }
}
