package net.medievalrp.spyglass.api.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import net.medievalrp.spyglass.api.util.BlockLocation;
import org.junit.jupiter.api.Test;

class RecordContextTest {

    private static RecordContext fresh() {
        return RecordContext.fresh(
                Instant.ofEpochSecond(1_700_000_000L), null,
                Origin.player(),
                Source.player(UUID.randomUUID(), "Alice"),
                new BlockLocation(UUID.randomUUID(), "world", 0, 0, 0),
                "srv");
    }

    @Test
    void freshHasEmptyExtensions() {
        assertThat(fresh().extensions()).isEmpty();
    }

    @Test
    void withExtensionAddsKeyAndLeavesTheOriginalUntouched() {
        RecordContext base = fresh();
        RecordContext withChannel = base.withExtension("channel", "#OOC");

        assertThat(base.extensions()).isEmpty();
        assertThat(withChannel.extensions()).hasSize(1).containsEntry("channel", "#OOC");
        // Only extensions differ; the rest of the context is carried over.
        assertThat(withChannel.id()).isEqualTo(base.id());
        assertThat(withChannel.source()).isEqualTo(base.source());
    }

    @Test
    void withExtensionChains() {
        RecordContext ctx = fresh().withExtension("channel", "#OOC").withExtension("faction", "Reach");
        assertThat(ctx.extensions())
                .containsEntry("channel", "#OOC")
                .containsEntry("faction", "Reach");
    }

    @Test
    void extensionsAreImmutable() {
        RecordContext ctx = fresh().withExtension("channel", "#OOC");
        assertThatThrownBy(() -> ctx.extensions().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void chatRecordCarriesContextExtensions() {
        ChatRecord chat = ChatRecord.of(fresh().withExtension("channel", "#OOC"), "#OOC", "hi", List.of());
        assertThat(chat.extensions()).containsEntry("channel", "#OOC");
    }

    @Test
    void recordWithoutAnExtensionsComponentDefaultsToEmpty() {
        // CommandRecord doesn't expose an extensions component, so it falls
        // back to EventRecord#extensions() — an empty map, never null.
        CommandRecord cmd = CommandRecord.of(fresh().withExtension("channel", "#OOC"), "tp", "/tp Alice");
        assertThat(cmd.extensions()).isEmpty();
    }
}
