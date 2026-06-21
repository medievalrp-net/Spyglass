package net.medievalrp.spyglass.api.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import net.medievalrp.spyglass.api.util.BlockLocation;
import org.junit.jupiter.api.Test;

class CustomRecordTest {

    private static final UUID PLAYER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID WORLD = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static RecordContext context(Map<String, String> extensions) {
        Instant now = Instant.parse("2026-06-20T12:00:00Z");
        return new RecordContext(
                UUID.randomUUID(), now, now.plusSeconds(60),
                Origin.player(), Source.player(PLAYER, "Alice"),
                new BlockLocation(WORLD, "world", 1, 64, 2), "srv", extensions);
    }

    @Test
    void ofMergesDataIntoExtensionsBag() {
        CustomRecord record = CustomRecord.of(
                context(Map.of("ctx_key", "ctx_val")),
                "voice", "voice to 2 players", "hello there",
                Map.of("voice_session_id", "42"));

        assertThat(record.event()).isEqualTo("voice");
        assertThat(record.target()).isEqualTo("voice to 2 players");
        assertThat(record.message()).isEqualTo("hello there");
        assertThat(record.extensions())
                .containsEntry("voice_session_id", "42")
                .containsEntry("ctx_key", "ctx_val");
    }

    @Test
    void ofWithoutDataKeepsContextExtensions() {
        CustomRecord record = CustomRecord.of(
                context(Map.of("ctx_key", "ctx_val")),
                "voice", "t", "m", null);

        assertThat(record.extensions()).containsExactlyEntriesOf(Map.of("ctx_key", "ctx_val"));
    }

    @Test
    void extensionsAreImmutable() {
        CustomRecord record = CustomRecord.of(context(Map.of()), "voice", "t", "m",
                Map.of("k", "v"));
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> record.extensions().put("x", "y"));
    }
}
