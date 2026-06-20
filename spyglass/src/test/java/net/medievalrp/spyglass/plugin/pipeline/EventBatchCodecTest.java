package net.medievalrp.spyglass.plugin.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.medievalrp.spyglass.api.event.EventRecord;
import net.medievalrp.spyglass.api.event.JoinRecord;
import net.medievalrp.spyglass.api.event.Origin;
import net.medievalrp.spyglass.api.event.Source;
import net.medievalrp.spyglass.api.util.BlockLocation;
import org.junit.jupiter.api.Test;

class EventBatchCodecTest {

    private static EventRecord record(UUID id, String name) {
        Instant now = Instant.now();
        return new JoinRecord(id, "join", now, now.plusSeconds(60),
                Origin.player(), Source.player(UUID.randomUUID(), name),
                new BlockLocation(UUID.randomUUID(), "world", 0, 64, 0),
                "srv", name, "127.0.0.1");
    }

    @Test
    void streamedEncodeRoundTripsEveryRecordInOrder() {
        // #125: encode streams records straight to the buffer (no intermediate
        // BsonArray/BsonDocument tree). The on-disk bytes must stay decodable,
        // in order, with every field intact.
        List<UUID> ids = new ArrayList<>();
        List<EventRecord> batch = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            UUID id = UUID.randomUUID();
            ids.add(id);
            batch.add(record(id, "player" + i));
        }

        List<EventRecord> decoded = EventBatchCodec.decode(EventBatchCodec.encode(batch));

        assertThat(decoded).hasSize(batch.size());
        assertThat(decoded.stream().map(EventRecord::id).toList())
                .as("streamed encode must round-trip every record, in order, by id")
                .containsExactlyElementsOf(ids);
        assertThat(((JoinRecord) decoded.get(7)).target())
                .as("record fields survive the streamed array-of-documents shape")
                .isEqualTo("player7");
    }

    @Test
    void encodesAnEmptyBatchToADecodableEmptyArray() {
        assertThat(EventBatchCodec.decode(EventBatchCodec.encode(List.of()))).isEmpty();
    }
}
