package net.medievalrp.spyglass.api.event;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EventCatalogTest {

    @Test
    void voiceIsRegisteredAsAChatRecord() {
        // Voice-chat transcripts arrive from an external integration with
        // event="voice". Without a registry entry the read path's
        // recordClassOf(...) returns null and every voice row is silently
        // dropped on decode. Pin it to ChatRecord so a:voice is searchable
        // and the rows round-trip through both backends.
        assertThat(EventCatalog.recordClassOf("voice")).isEqualTo(ChatRecord.class);
    }

    @Test
    void recordClassLookupIsCaseInsensitive() {
        assertThat(EventCatalog.recordClassOf("VOICE")).isEqualTo(ChatRecord.class);
    }

    @Test
    void unknownEventResolvesToNull() {
        assertThat(EventCatalog.recordClassOf("not-an-event")).isNull();
        assertThat(EventCatalog.recordClassOf(null)).isNull();
    }
}
