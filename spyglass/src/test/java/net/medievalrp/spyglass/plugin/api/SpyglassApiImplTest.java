package net.medievalrp.spyglass.plugin.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.mock;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import net.medievalrp.spyglass.api.event.Origin;
import net.medievalrp.spyglass.api.event.Source;
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

    // #230: a null location poisons storage drains downstream; the boundary
    // must reject it loudly, naming the offending event, before it reaches
    // the recorder.
    @Test
    void nullLocationRecordRejectedAtIntake() {
        Recorder recorder = mock(Recorder.class);
        SpyglassApiImpl api = new SpyglassApiImpl(
                recorder, mock(RecordStore.class), Runnable::run,
                ConcurrentHashMap.newKeySet(), null, "srv", Logger.getLogger("test"));

        CustomRecord poison = new CustomRecord(
                UUID.randomUUID(), "third-party-global", Instant.now(),
                Instant.now().plusSeconds(3600), Origin.plugin("some-plugin"),
                Source.plugin("some-plugin"), null /* the poison */, "srv",
                null, null, Map.of());

        assertThatThrownBy(() -> api.record(poison))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("third-party-global")
                .hasMessageContaining("null location");
        verifyNoInteractions(recorder);
    }

    @Test
    void builtinEventsReportAsRegistered() {
        SpyglassApiImpl api = api(ConcurrentHashMap.newKeySet());
        assertThat(api.isEventRegistered("say")).isTrue();
        assertThat(api.isEventRegistered("not-a-real-event")).isFalse();
    }
}
